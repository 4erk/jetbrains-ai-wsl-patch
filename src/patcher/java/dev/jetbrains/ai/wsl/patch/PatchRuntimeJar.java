package dev.jetbrains.ai.wsl.patch;

import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode;
import org.jetbrains.org.objectweb.asm.tree.ClassNode;
import org.jetbrains.org.objectweb.asm.tree.InsnList;
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public final class PatchRuntimeJar {
    private static final String PROCESS_ENTRY =
        "com/intellij/ml/llm/agents/acp/process/AcpProcessHandlerService.class";
    private static final String LAUNCHER_OWNER = "com/intellij/platform/acp/AcpProcessLauncher";
    private static final String LAUNCHER_COMPANION_OWNER = LAUNCHER_OWNER + "$Companion";
    private static final String START_CONFIG_DESC = "Lcom/intellij/platform/acp/AcpAgentStartConfig;";
    private static final String PATH_DESC = "Ljava/nio/file/Path;";
    private static final String HELPER_ENTRY =
        "com/intellij/ml/llm/agents/acp/process/CodexRuntimePatchSupport.class";
    private static final String HELPER_OWNER =
        "com/intellij/ml/llm/agents/acp/process/CodexRuntimePatchSupport";
    private static final String NORMALIZE_DESC =
        "(" + START_CONFIG_DESC + PATH_DESC + ")" + START_CONFIG_DESC;
    private static final String PATCH_METADATA_ENTRY = "META-INF/jetbrains-ai-wsl-patch.properties";

    private PatchRuntimeJar() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: PatchRuntimeJar <input-jar> <output-jar> <compiled-classes-root> <patch-metadata>"
            );
        }

        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path compiledRoot = Path.of(args[2]);
        Map<String, byte[]> helperClasses = readClassFamily(compiledRoot, HELPER_ENTRY);
        helperClasses.put(PATCH_METADATA_ENTRY, Files.readAllBytes(Path.of(args[3])));
        boolean patched = false;

        Files.createDirectories(output.toAbsolutePath().getParent());
        try (JarFile source = new JarFile(input.toFile());
             JarOutputStream target = new JarOutputStream(Files.newOutputStream(output))) {
            Enumeration<JarEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (helperClasses.containsKey(entry.getName())) {
                    throw new IllegalStateException("Input runtime jar is already patched: " + entry.getName());
                }

                byte[] data;
                try (InputStream stream = source.getInputStream(entry)) {
                    data = stream.readAllBytes();
                }
                if (PROCESS_ENTRY.equals(entry.getName())) {
                    data = patchProcessService(data);
                    patched = true;
                }
                writeEntry(target, entry, data);
            }

            for (Map.Entry<String, byte[]> helper : helperClasses.entrySet()) {
                writeEntry(target, new JarEntry(helper.getKey()), helper.getValue());
            }
        }

        if (!patched) {
            Files.deleteIfExists(output);
            throw new IllegalStateException("Runtime anchor not found: " + PROCESS_ENTRY);
        }
    }

    private static byte[] patchProcessService(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);
        int hookCount = 0;
        for (MethodNode method : node.methods) {
            if ("getOrCreateProcessHandler".equals(method.name) && patchBeforeLaunch(method)) {
                hookCount++;
            }
        }
        if (hookCount != 1) {
            throw new IllegalStateException("Expected exactly one ACP launch hook, found " + hookCount);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean patchBeforeLaunch(MethodNode method) {
        MethodInsnNode startProcess = null;
        MethodInsnNode getLauncher = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                || !LAUNCHER_OWNER.equals(call.owner)
                || !"startProcess".equals(call.name)
                || !call.desc.contains(START_CONFIG_DESC)) {
                continue;
            }
            startProcess = call;
            getLauncher = findLauncherGetInstance(call);
            break;
        }
        if (startProcess == null || getLauncher == null) {
            return false;
        }

        int configVariable = findStartConfigVariable(getLauncher, startProcess);
        VarInsnNode projectDirStore = findProjectDirStore(method);
        if (configVariable < 0 || projectDirStore == null) {
            throw new IllegalStateException("ACP launch shape changed: config or project path local is missing");
        }

        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, configVariable));
        hook.add(new VarInsnNode(Opcodes.ALOAD, projectDirStore.var));
        hook.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            HELPER_OWNER,
            "normalizeStartConfig",
            NORMALIZE_DESC,
            false
        ));
        hook.add(new VarInsnNode(Opcodes.ASTORE, configVariable));
        method.instructions.insert(projectDirStore, hook);
        return true;
    }

    private static MethodInsnNode findLauncherGetInstance(MethodInsnNode startProcess) {
        for (AbstractInsnNode current = startProcess.getPrevious(); current != null; current = current.getPrevious()) {
            if (current instanceof MethodInsnNode call
                && LAUNCHER_COMPANION_OWNER.equals(call.owner)
                && "getInstance".equals(call.name)) {
                return call;
            }
        }
        return null;
    }

    private static int findStartConfigVariable(AbstractInsnNode getLauncher, AbstractInsnNode startProcess) {
        int objectLoads = 0;
        for (AbstractInsnNode current = nextMeaningful(getLauncher);
             current != null && current != startProcess;
             current = nextMeaningful(current)) {
            if (current instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD) {
                objectLoads++;
                if (objectLoads == 2) {
                    return variable.var;
                }
            }
        }
        return -1;
    }

    private static VarInsnNode findProjectDirStore(MethodNode method) {
        for (AbstractInsnNode current = method.instructions.getFirst(); current != null; current = current.getNext()) {
            if (!(current instanceof MethodInsnNode call)
                || !"com/intellij/openapi/vfs/VirtualFile".equals(call.owner)
                || !"toNioPath".equals(call.name)
                || !("()" + PATH_DESC).equals(call.desc)) {
                continue;
            }
            int remaining = 40;
            for (AbstractInsnNode candidate = call.getNext();
                 candidate != null && remaining-- > 0;
                 candidate = candidate.getNext()) {
                if (candidate instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ASTORE) {
                    return variable;
                }
            }
        }
        return null;
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode current) {
        for (AbstractInsnNode next = current == null ? null : current.getNext();
             next != null;
             next = next.getNext()) {
            int type = next.getType();
            if (type != AbstractInsnNode.LABEL
                && type != AbstractInsnNode.LINE
                && type != AbstractInsnNode.FRAME) {
                return next;
            }
        }
        return null;
    }

    private static Map<String, byte[]> readClassFamily(Path compiledRoot, String entryName) throws IOException {
        int slash = entryName.lastIndexOf('/');
        String packageName = entryName.substring(0, slash);
        String className = entryName.substring(slash + 1, entryName.length() - ".class".length());
        Path packageDir = compiledRoot.resolve(packageName.replace('/', java.io.File.separatorChar));
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (var files = Files.list(packageDir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (name.equals(className + ".class") || name.startsWith(className + "$")) {
                    classes.put(packageName + "/" + name, Files.readAllBytes(file));
                }
            }
        }
        if (!classes.containsKey(entryName)) {
            throw new IOException("Compiled helper not found: " + entryName);
        }
        return classes;
    }

    private static void writeEntry(JarOutputStream target, JarEntry source, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(source.getName());
        if (source.getTime() >= 0) {
            entry.setTime(source.getTime());
        } else {
            entry.setTime(0L);
        }
        target.putNextEntry(entry);
        if (!source.isDirectory()) {
            target.write(bytes);
        }
        target.closeEntry();
    }
}
