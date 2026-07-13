package dev.jetbrains.ai.wsl.patch;

import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.Type;
import org.jetbrains.org.objectweb.asm.tree.ClassNode;
import org.jetbrains.org.objectweb.asm.tree.InsnList;
import org.jetbrains.org.objectweb.asm.tree.InsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public final class PatchFrontendJar {
    private static final String MARKDOWN_NAV_ENTRY =
        "com/intellij/ml/llm/agents/frontend/compose/ui/components/utils/MarkdownNavigationKt.class";
    private static final String HELPER_ENTRY =
        "com/intellij/ml/llm/agents/frontend/compose/ui/components/utils/MarkdownWslLinkPatchSupport.class";
    private static final String HELPER_OWNER =
        "com/intellij/ml/llm/agents/frontend/compose/ui/components/utils/MarkdownWslLinkPatchSupport";
    private static final String PATCH_METADATA_ENTRY = "META-INF/jetbrains-ai-wsl-patch.properties";

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: PatchFrontendJar <input-jar> <output-jar> <compiled-classes-root> <patch-metadata>");
            System.exit(2);
        }

        Path inputJar = Path.of(args[0]);
        Path outputJar = Path.of(args[1]);
        Path compiledRoot = Path.of(args[2]);

        Map<String, byte[]> extraEntries = new HashMap<>();
        extraEntries.put(HELPER_ENTRY, readClass(compiledRoot, HELPER_ENTRY));
        extraEntries.put(PATCH_METADATA_ENTRY, Files.readAllBytes(Path.of(args[3])));

        boolean markdownPatched = false;

        try (JarFile jarFile = new JarFile(inputJar.toFile());
             JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(outputJar))) {

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (PATCH_METADATA_ENTRY.equals(entry.getName())) {
                    throw new IllegalStateException("Input frontend jar is already patched");
                }
                byte[] data;
                try (InputStream in = jarFile.getInputStream(entry)) {
                    data = in.readAllBytes();
                }

                if (MARKDOWN_NAV_ENTRY.equals(entry.getName())) {
                    data = patchMarkdownNavigation(data);
                    markdownPatched = true;
                } else if (HELPER_ENTRY.equals(entry.getName())) {
                    data = extraEntries.remove(HELPER_ENTRY);
                }

                JarEntry outEntry = new JarEntry(entry.getName());
                outEntry.setTime(entry.getTime());
                jarOut.putNextEntry(outEntry);
                jarOut.write(data);
                jarOut.closeEntry();
            }

            for (Map.Entry<String, byte[]> extra : extraEntries.entrySet()) {
                if (jarFile.getEntry(extra.getKey()) != null) {
                    continue;
                }
                JarEntry outEntry = new JarEntry(extra.getKey());
                outEntry.setTime(0L);
                jarOut.putNextEntry(outEntry);
                jarOut.write(extra.getValue());
                jarOut.closeEntry();
            }
        }

        if (!markdownPatched) {
            throw new IllegalStateException("Failed to patch " + MARKDOWN_NAV_ENTRY);
        }

        System.out.println("Patched frontend markdown link navigation.");
    }

    private static byte[] readClass(Path compiledRoot, String entryName) throws IOException {
        Path classFile = compiledRoot.resolve(entryName.replace('/', java.io.File.separatorChar));
        if (!Files.exists(classFile)) {
            throw new IOException("Compiled helper class not found: " + classFile);
        }
        return Files.readAllBytes(classFile);
    }

    private static byte[] patchMarkdownNavigation(byte[] classBytes) {
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);

        boolean browserPatched = false;
        boolean localFileCandidatePatched = false;
        for (MethodNode method : classNode.methods) {
            if (!"markdownUrlClickHandler$lambda$0".equals(method.name)
                && !"createMarkdownUrlClickHandler$lambda$0".equals(method.name)) {
                continue;
            }

            boolean methodHasLocalFileCandidatePatch = hasLocalFileCandidatePatch(method);
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode methodInsn)) {
                    continue;
                }

                if ("com/intellij/ml/llm/agents/frontend/compose/ui/components/utils/ChatReferenceManager$Companion".equals(methodInsn.owner)
                    && "getLocalFilePathCandidate".equals(methodInsn.name)
                    && "(Ljava/lang/String;)Ljava/lang/String;".equals(methodInsn.desc)) {
                    if (!methodHasLocalFileCandidatePatch) {
                        int urlVar = getLastArgumentLocalIndex(method);
                        InsnList patch = new InsnList();
                        patch.add(new VarInsnNode(Opcodes.ALOAD, urlVar));
                        patch.add(new InsnNode(Opcodes.SWAP));
                        patch.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            HELPER_OWNER,
                            "normalizeLocalFilePathCandidate",
                            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                            false
                        ));
                        method.instructions.insert(methodInsn, patch);
                        methodHasLocalFileCandidatePatch = true;
                    }
                    localFileCandidatePatched = true;
                    continue;
                }

                if (HELPER_OWNER.equals(methodInsn.owner)
                    && "openPathOrUrl".equals(methodInsn.name)
                    && "(Ljava/lang/String;)V".equals(methodInsn.desc)) {
                    browserPatched = true;
                    continue;
                }

                if (!"com/intellij/ide/BrowserUtil".equals(methodInsn.owner)
                    || !"open".equals(methodInsn.name)
                    || !"(Ljava/lang/String;)V".equals(methodInsn.desc)) {
                    continue;
                }

                methodInsn.owner = HELPER_OWNER;
                methodInsn.name = "openPathOrUrl";
                methodInsn.desc = "(Ljava/lang/String;)V";
                methodInsn.itf = false;
                browserPatched = true;
            }
        }

        if (!localFileCandidatePatched) {
            throw new IllegalStateException("ChatReferenceManager.getLocalFilePathCandidate(...) call site not found in MarkdownNavigationKt");
        }
        if (!browserPatched) {
            throw new IllegalStateException("BrowserUtil.open(...) call site not found in MarkdownNavigationKt");
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static boolean hasLocalFileCandidatePatch(MethodNode method) {
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode methodInsn)) {
                continue;
            }
            if (HELPER_OWNER.equals(methodInsn.owner)
                && "normalizeLocalFilePathCandidate".equals(methodInsn.name)
                && "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;".equals(methodInsn.desc)) {
                return true;
            }
        }
        return false;
    }

    private static int getLastArgumentLocalIndex(MethodNode method) {
        int index = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        int last = index;
        for (Type argumentType : Type.getArgumentTypes(method.desc)) {
            last = index;
            index += argumentType.getSize();
        }
        return last;
    }
}
