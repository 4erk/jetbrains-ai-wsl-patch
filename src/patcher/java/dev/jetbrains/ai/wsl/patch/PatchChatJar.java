package dev.jetbrains.ai.wsl.patch;

import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.tree.ClassNode;
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.VarInsnNode;
import org.jetbrains.org.objectweb.asm.tree.InsnList;

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

public final class PatchChatJar {
    private static final String FACTORY_ENTRY =
        "com/intellij/ml/llm/core/chat/ui/chat/AIAssistantChatPanelUiFactory.class";
    private static final String FACTORY_OWNER =
        "com/intellij/ml/llm/core/chat/ui/chat/AIAssistantChatPanelUiFactory";
    private static final String NOTIFICATION_SERVICE_ENTRY =
        "com/intellij/ml/llm/chat/notifications/ChatNotificationService.class";
    private static final String NOTIFICATION_SERVICE_OWNER =
        "com/intellij/ml/llm/chat/notifications/ChatNotificationService";
    private static final String HELPER_PREFIX =
        "com/intellij/ml/llm/core/chat/ui/chat/CodexUsageLimitPatchSupport";
    private static final String HELPER_OWNER =
        "com/intellij/ml/llm/core/chat/ui/chat/CodexUsageLimitPatchSupport";
    private static final String SOUND_HELPER_PREFIX =
        "com/intellij/ml/llm/chat/notifications/AgentCompletionSoundPatchSupport";
    private static final String SOUND_HELPER_OWNER =
        "com/intellij/ml/llm/chat/notifications/AgentCompletionSoundPatchSupport";
    private static final String PATCH_METADATA_ENTRY = "META-INF/jetbrains-ai-wsl-patch.properties";

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: PatchChatJar <input-jar> <output-jar> <compiled-classes-root> <patch-metadata>");
            System.exit(2);
        }

        Path inputJar = Path.of(args[0]);
        Path outputJar = Path.of(args[1]);
        Path compiledRoot = Path.of(args[2]);

        Map<String, byte[]> extraEntries = readClassFamily(compiledRoot, HELPER_PREFIX);
        extraEntries.putAll(readClassFamily(compiledRoot, SOUND_HELPER_PREFIX));
        extraEntries.put(PATCH_METADATA_ENTRY, Files.readAllBytes(Path.of(args[3])));
        boolean factoryPatched = false;
        boolean notificationServicePatched = false;

        try (JarFile jarFile = new JarFile(inputJar.toFile());
             JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(outputJar))) {

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (PATCH_METADATA_ENTRY.equals(entry.getName())) {
                    throw new IllegalStateException("Input chat jar is already patched");
                }
                byte[] data;
                try (InputStream in = jarFile.getInputStream(entry)) {
                    data = in.readAllBytes();
                }

                if (FACTORY_ENTRY.equals(entry.getName())) {
                    data = patchChatPanelFactory(data);
                    factoryPatched = true;
                } else if (NOTIFICATION_SERVICE_ENTRY.equals(entry.getName())) {
                    data = patchChatNotificationService(data);
                    notificationServicePatched = true;
                } else if (entry.getName().startsWith(HELPER_PREFIX) && entry.getName().endsWith(".class")) {
                    byte[] replacement = extraEntries.remove(entry.getName());
                    if (replacement != null) {
                        data = replacement;
                    }
                } else if (entry.getName().startsWith(SOUND_HELPER_PREFIX) && entry.getName().endsWith(".class")) {
                    byte[] replacement = extraEntries.remove(entry.getName());
                    if (replacement != null) {
                        data = replacement;
                    }
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

        if (!factoryPatched) {
            throw new IllegalStateException("Failed to patch " + FACTORY_ENTRY);
        }
        if (!notificationServicePatched) {
            throw new IllegalStateException("Failed to patch " + NOTIFICATION_SERVICE_ENTRY);
        }

        System.out.println("Patched chat input usage limit button and focused completion sound.");
    }

    private static Map<String, byte[]> readClassFamily(Path compiledRoot, String entryPrefix) throws IOException {
        Path packageDir = compiledRoot.resolve(entryPrefix.substring(0, entryPrefix.lastIndexOf('/'))
            .replace('/', java.io.File.separatorChar));
        String classBase = entryPrefix.substring(entryPrefix.lastIndexOf('/') + 1);
        Map<String, byte[]> result = new HashMap<>();
        if (!Files.isDirectory(packageDir)) {
            throw new IOException("Compiled helper package not found: " + packageDir);
        }
        try (var stream = Files.list(packageDir)) {
            for (Path classFile : stream.toList()) {
                String fileName = classFile.getFileName().toString();
                if (!fileName.equals(classBase + ".class")
                    && !fileName.startsWith(classBase + "$")
                    && !fileName.startsWith(classBase + "$$")) {
                    continue;
                }
                if (!fileName.endsWith(".class")) {
                    continue;
                }
                String entryName = entryPrefix.substring(0, entryPrefix.lastIndexOf('/') + 1) + fileName;
                result.put(entryName, Files.readAllBytes(classFile));
            }
        }
        if (result.isEmpty()) {
            throw new IOException("Compiled helper classes not found for " + entryPrefix);
        }
        return result;
    }

    private static byte[] patchChatPanelFactory(byte[] classBytes) {
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);

        boolean patched = false;
        for (MethodNode method : classNode.methods) {
            if (!"createInputAreaPanel".equals(method.name) || !"()Ljavax/swing/JPanel;".equals(method.desc)) {
                continue;
            }
            if (hasInstallCall(method)) {
                patched = true;
                continue;
            }
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof VarInsnNode varInsn)) {
                    continue;
                }
                if (varInsn.getOpcode() != Opcodes.ASTORE || varInsn.var != 4) {
                    continue;
                }
                if (!hasShareFeedbackLabelBefore(insn, 140)) {
                    continue;
                }
                InsnList patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 4));
                patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                patch.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    FACTORY_OWNER,
                    "shareFeedbackLabel",
                    "Lcom/intellij/ui/components/ActionLink;"
                ));
                patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                patch.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    FACTORY_OWNER,
                    "input",
                    "Lcom/intellij/ml/llm/core/chat/ui/chat/input/AIAssistantInput;"
                ));
                patch.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    HELPER_OWNER,
                    "install",
                    "(Ljavax/swing/JPanel;Ljavax/swing/JComponent;Lcom/intellij/ml/llm/core/chat/ui/chat/input/AIAssistantInput;)V",
                    false
                ));
                method.instructions.insert(insn, patch);
                patched = true;
                break;
            }
        }

        if (!patched) {
            throw new IllegalStateException("Feedback panel insertion point not found in AIAssistantChatPanelUiFactory.createInputAreaPanel");
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] patchChatNotificationService(byte[] classBytes) {
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);

        boolean patched = false;
        for (MethodNode method : classNode.methods) {
            if (!"changeAgentState".equals(method.name)
                || !"(Lcom/intellij/ml/llm/chat/shared/ChatSessionAgentId;Lcom/intellij/ml/llm/aui/events/extensions/AUITaskState;)Lcom/intellij/ml/llm/chat/shared/ChatSessionNotification;".equals(method.desc)) {
                continue;
            }
            if (hasSoundCall(method)) {
                patched = true;
                continue;
            }
            for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof VarInsnNode varInsn)) {
                    continue;
                }
                if (varInsn.getOpcode() != Opcodes.ASTORE || varInsn.var != 5) {
                    continue;
                }
                if (!(varInsn.getPrevious() instanceof MethodInsnNode previousCall)
                    || !"com/intellij/ml/llm/chat/shared/ChatSessionNotification".equals(previousCall.owner)
                    || !"<init>".equals(previousCall.name)) {
                    continue;
                }

                InsnList patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                patch.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    NOTIFICATION_SERVICE_OWNER,
                    "isToolWindowFocused",
                    "Z"
                ));
                patch.add(new VarInsnNode(Opcodes.ALOAD, 3));
                patch.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    SOUND_HELPER_OWNER,
                    "playIfFocused",
                    "(ZLcom/intellij/ml/llm/chat/shared/ChatSessionNotificationType;)V",
                    false
                ));
                method.instructions.insert(insn, patch);
                patched = true;
                break;
            }
        }

        if (!patched) {
            throw new IllegalStateException("Focused completion sound insertion point not found in ChatNotificationService.changeAgentState");
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static boolean hasInstallCall(MethodNode method) {
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode methodInsn)) {
                continue;
            }
            if (HELPER_OWNER.equals(methodInsn.owner)
                && "install".equals(methodInsn.name)
                && "(Ljavax/swing/JPanel;Ljavax/swing/JComponent;Lcom/intellij/ml/llm/core/chat/ui/chat/input/AIAssistantInput;)V".equals(methodInsn.desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSoundCall(MethodNode method) {
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode methodInsn)) {
                continue;
            }
            if (SOUND_HELPER_OWNER.equals(methodInsn.owner)
                && "playIfFocused".equals(methodInsn.name)
                && "(ZLcom/intellij/ml/llm/chat/shared/ChatSessionNotificationType;)V".equals(methodInsn.desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasShareFeedbackLabelBefore(org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode insn, int maxSteps) {
        int steps = 0;
        for (var cursor = insn.getPrevious(); cursor != null && steps < maxSteps; cursor = cursor.getPrevious(), steps++) {
            if (!(cursor instanceof FieldInsnNode fieldInsn)) {
                continue;
            }
            if (FACTORY_OWNER.equals(fieldInsn.owner) && "shareFeedbackLabel".equals(fieldInsn.name)) {
                return true;
            }
        }
        return false;
    }
}
