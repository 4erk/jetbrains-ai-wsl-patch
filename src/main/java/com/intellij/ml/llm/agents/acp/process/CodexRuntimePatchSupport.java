package com.intellij.ml.llm.agents.acp.process;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.platform.acp.AcpAgentStartConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CodexRuntimePatchSupport {
    private static final Logger LOG = Logger.getInstance(CodexRuntimePatchSupport.class);
    private static final String WSL_LOCALHOST_PREFIX = "\\\\wsl.localhost\\";
    private static final String WSL_DOLLAR_PREFIX = "\\\\wsl$\\";
    private static final long AUTH_SYNC_THROTTLE_MILLIS = 20_000L;
    private static final AtomicBoolean SYNC_RUNNING = new AtomicBoolean();
    private static volatile long lastAuthSyncMillis;

    private CodexRuntimePatchSupport() {}

    public static AcpAgentStartConfig normalizeStartConfig(AcpAgentStartConfig config, Path projectDir) {
        if (config == null || !isCodexConfig(config)) {
            return config;
        }
        Map<String, String> runtime = loadRuntimeConfig();
        WslProjectContext context = detectWslProject(projectDir);
        return normalize(config, context, runtime);
    }

    private static AcpAgentStartConfig normalize(
        AcpAgentStartConfig config,
        WslProjectContext context,
        Map<String, String> runtime
    ) {
        if (runtime.isEmpty()) {
            LOG.warn("Codex runtime manifest is missing; preserving JetBrains ACP command");
            return config;
        }

        if (context != null) {
            context = context.withDefaults(runtime);
            String prefix = "WSL_" + configKey(context.distribution) + "_";
            String node = firstNonBlank(runtime.get(prefix + "NODE"), runtime.get("WSL_NODE"));
            String acp = firstNonBlank(runtime.get(prefix + "ACP_ENTRY"), runtime.get("WSL_ACP_ENTRY"));
            String codex = firstNonBlank(runtime.get(prefix + "CODEX"), runtime.get("WSL_CODEX"));
            String codexHome = firstNonBlank(
                runtime.get(prefix + "CODEX_HOME"),
                runtime.get("WSL_CODEX_HOME"),
                context.homePath + "/.local/share/JetBrains/" + productProfile() + "/aia/codex"
            );
            if (node == null || acp == null || codex == null) {
                LOG.warn("Codex WSL runtime is incomplete for distribution " + context.distribution);
                return config;
            }

            syncAuthWithWsl(context);
            Map<String, String> env = normalizeEnv(config.getEnv(), codexHome, codex, "wsl:" + context.distribution);
            LOG.info("Codex runtime selected: WSL " + context.distribution + "/" + context.user + ", command=" + node);
            return newStartConfig(node, List.of(acp), Collections.emptyList(), env);
        }

        String node = firstNonBlank(runtime.get("WINDOWS_NODE"), runtime.get("NATIVE_NODE"));
        String acp = firstNonBlank(runtime.get("WINDOWS_ACP_ENTRY"), runtime.get("NATIVE_ACP_ENTRY"));
        String codex = firstNonBlank(runtime.get("WINDOWS_CODEX"), runtime.get("NATIVE_CODEX"));
        String codexHome = firstNonBlank(
            runtime.get("WINDOWS_CODEX_HOME"),
            runtime.get("NATIVE_CODEX_HOME"),
            getJetBrainsCodexHome().toString()
        );
        if (node == null || acp == null || codex == null) {
            return config;
        }

        Map<String, String> env = normalizeEnv(config.getEnv(), codexHome, codex, isWindowsHost() ? "windows" : "native");
        LOG.info("Codex runtime selected: " + (isWindowsHost() ? "Windows" : "native") + ", command=" + node);
        return newStartConfig(node, List.of(acp), Collections.emptyList(), env);
    }

    private static boolean isCodexConfig(AcpAgentStartConfig config) {
        if (containsCodex(config.getCommand())
            || containsCodex(config.getBaseArgs())
            || containsCodex(config.getArgs())) {
            return true;
        }
        Map<String, String> env = config.getEnv();
        return env != null && (env.containsKey("CODEX_PATH") || env.containsKey("CODEX_HOME"));
    }

    private static boolean containsCodex(Object value) {
        return value != null && value.toString().toLowerCase(Locale.ROOT).contains("codex");
    }

    private static Map<String, String> loadRuntimeConfig() {
        Path path = runtimeConfigPath();
        if (path == null || !Files.isRegularFile(path)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                values.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
            }
            return values;
        }
        catch (IOException error) {
            LOG.warn("Failed to read Codex runtime manifest " + path, error);
            return Collections.emptyMap();
        }
    }

    private static Path runtimeConfigPath() {
        if (isWindowsHost()) {
            return getJetBrainsCodexHome().resolve("jetbrains-ai-wsl-patch.env");
        }
        String home = System.getProperty("user.home");
        return home == null || home.isBlank()
            ? null
            : Path.of(home, ".local", "share", "JetBrains", productProfile(), "aia", "codex", "jetbrains-ai-wsl-patch.env");
    }

    private static String configKey(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String pathJoin(String parent, String child) {
        String separator = parent.contains("\\") && !parent.contains("/") ? "\\" : "/";
        return parent.endsWith("/") || parent.endsWith("\\") ? parent + child : parent + separator + child;
    }

    private static Map<String, String> normalizeEnv(
        Map<String, String> source,
        String codexHome,
        String codexPath,
        String runtimeKind
    ) {
        Map<String, String> env = new LinkedHashMap<>();
        if (source != null) {
            env.putAll(source);
        }

        env.put("CODEX_HOME", codexHome);
        env.put("CODEX_PATH", codexPath);
        env.put("APP_SERVER_LOGS", pathJoin(codexHome, "logs"));
        env.put("JETBRAINS_AI_WSL_PATCH", "1");
        env.put("JETBRAINS_AI_WSL_PATCH_RUNTIME", runtimeKind);
        return env;
    }

    private static void syncAuthWithWsl(WslProjectContext context) {
        long now = System.currentTimeMillis();
        if (now - lastAuthSyncMillis < AUTH_SYNC_THROTTLE_MILLIS || !SYNC_RUNNING.compareAndSet(false, true)) {
            return;
        }
        lastAuthSyncMillis = now;
        CompletableFuture.runAsync(() -> {
            try {
                doSyncAuthWithWsl(context);
            }
            catch (Throwable t) {
                LOG.info("Codex auth sync skipped for WSL " + context.distribution + "/" + context.user, t);
            }
            finally {
                SYNC_RUNNING.set(false);
            }
        });
    }

    private static void doSyncAuthWithWsl(WslProjectContext context) throws Exception {
        syncFileWithWsl(context, "auth.json");
        syncConfigWithinPlatforms(context);
    }

    private static void syncConfigWithinPlatforms(WslProjectContext context) throws Exception {
        syncLocalFiles(getJetBrainsCodexHome().resolve("config.toml"), getUserCodexHome().resolve("config.toml"));
        syncWslFiles(
            context,
            context.homePath + "/.codex/config.toml",
            wslJetBrainsCodexHome(context) + "/config.toml"
        );
    }

    private static void syncWslFiles(WslProjectContext context, String leftPath, String rightPath) throws Exception {
        WslFileState left = readWslFileState(context, leftPath);
        WslFileState right = readWslFileState(context, rightPath);
        WslFileState newest;
        WslFileState oldest;
        if (!left.exists && !right.exists) {
            return;
        }
        if (left.exists && (!right.exists || left.modifiedEpochSeconds >= right.modifiedEpochSeconds)) {
            newest = left;
            oldest = right;
        }
        else {
            newest = right;
            oldest = left;
        }

        byte[] bytes = readWslFile(context, newest.path);
        writeWslFileIfChanged(context, oldest.path, oldest, bytes, sha256Hex(bytes));
    }

    private static void syncFileWithWsl(WslProjectContext context, String fileName) throws Exception {
        Path jetBrainsFile = getJetBrainsCodexHome().resolve(fileName);
        Path userFile = getUserCodexHome().resolve(fileName);
        Path newestLocal = syncLocalFiles(jetBrainsFile, userFile);
        String sharedWslPath = context.homePath + "/.codex/" + fileName;
        String ideaWslPath = wslJetBrainsCodexHome(context) + "/" + fileName;
        WslFileState sharedWsl = readWslFileState(context, sharedWslPath);
        WslFileState ideaWsl = readWslFileState(context, ideaWslPath);

        FileCandidate newest = newestCandidate(newestLocal, sharedWsl, ideaWsl);
        if (newest == null) {
            return;
        }

        byte[] bytes = newest.readBytes(context);
        String hash = sha256Hex(bytes);
        writeLocalFileIfChanged(jetBrainsFile, bytes, hash);
        writeLocalFileIfChanged(userFile, bytes, hash);
        writeWslFileIfChanged(context, sharedWslPath, sharedWsl, bytes, hash);
        writeWslFileIfChanged(context, ideaWslPath, ideaWsl, bytes, hash);
    }

    private static FileCandidate newestCandidate(Path local, WslFileState sharedWsl, WslFileState ideaWsl) throws IOException {
        FileCandidate newest = null;
        if (local != null) {
            newest = new FileCandidate(local, null, Files.getLastModifiedTime(local).toMillis() / 1000L);
        }
        if (sharedWsl.exists) {
            newest = newer(newest, new FileCandidate(null, sharedWsl.path, sharedWsl.modifiedEpochSeconds));
        }
        if (ideaWsl.exists) {
            newest = newer(newest, new FileCandidate(null, ideaWsl.path, ideaWsl.modifiedEpochSeconds));
        }
        return newest;
    }

    private static FileCandidate newer(FileCandidate current, FileCandidate next) {
        if (current == null || next.modifiedEpochSeconds > current.modifiedEpochSeconds) {
            return next;
        }
        return current;
    }

    private static Path syncLocalFiles(Path left, Path right) throws IOException {
        boolean leftExists = Files.exists(left);
        boolean rightExists = Files.exists(right);
        if (!leftExists && !rightExists) {
            return null;
        }
        if (leftExists && !rightExists) {
            copyLocalFile(left, right);
            return left;
        }
        if (!leftExists) {
            copyLocalFile(right, left);
            return right;
        }

        byte[] leftBytes = Files.readAllBytes(left);
        byte[] rightBytes = Files.readAllBytes(right);
        if (sha256Hex(leftBytes).equals(sha256Hex(rightBytes))) {
            return Files.getLastModifiedTime(left).toMillis() >= Files.getLastModifiedTime(right).toMillis()
                ? left
                : right;
        }

        Path newest = Files.getLastModifiedTime(left).toMillis() >= Files.getLastModifiedTime(right).toMillis()
            ? left
            : right;
        Path oldest = newest == left ? right : left;
        copyLocalFile(newest, oldest);
        return newest;
    }

    private static void copyLocalFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void writeLocalFileIfChanged(Path target, byte[] bytes, String hash) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target) && sha256Hex(Files.readAllBytes(target)).equals(hash)) {
            return;
        }
        Files.write(target, bytes);
    }

    private static WslFileState readWslFileState(WslProjectContext context, String path) throws Exception {
        String quotedPath = shellQuote(path);
        ProcessResult result = runProcess(8, "wsl.exe", "-d", context.distribution, "-u", context.user,
            "--", "bash", "-lc",
            "if [ -f " + quotedPath + " ]; then stat -c '%Y %s' " + quotedPath
                + "; sha256sum " + quotedPath + " | cut -d ' ' -f 1; else echo missing; fi");
        if (result.exitCode != 0) {
            LOG.info("Failed to inspect WSL Codex file " + path + ": " + result.output);
            return WslFileState.missing(path);
        }

        String[] lines = result.output.strip().split("\\R");
        if (lines.length == 0 || "missing".equals(lines[0])) {
            return WslFileState.missing(path);
        }

        String[] stat = lines[0].trim().split("\\s+");
        if (stat.length < 2 || lines.length < 2) {
            return WslFileState.missing(path);
        }

        return new WslFileState(path, true, Long.parseLong(stat[0]), Long.parseLong(stat[1]), lines[1].trim());
    }

    private static byte[] readWslFile(WslProjectContext context, String path) throws Exception {
        Process process = new ProcessBuilder("wsl.exe", "-d", context.distribution, "-u", context.user,
            "--", "cat", path)
            .redirectErrorStream(true)
            .start();
        byte[] output = readProcessOutput(process, 8);
        if (process.exitValue() != 0) {
            throw new IOException("Failed to read WSL Codex file " + path + ": " + new String(output, StandardCharsets.UTF_8));
        }
        return output;
    }

    private static void writeWslFileIfChanged(
        WslProjectContext context,
        String file,
        WslFileState current,
        byte[] bytes,
        String hash
    ) throws Exception {
        if (current.exists && hash.equalsIgnoreCase(current.sha256)) {
            return;
        }
        writeWslFile(context, file, bytes);
    }

    private static void writeWslFile(WslProjectContext context, String file, byte[] bytes) throws Exception {
        int slash = file.lastIndexOf('/');
        String dir = slash > 0 ? file.substring(0, slash) : context.homePath + "/.codex";
        String tmp = file + ".tmp-codex-sync-" + System.nanoTime();
        String script = "set -e; mkdir -p " + shellQuote(dir)
            + "; umask 077; cat > " + shellQuote(tmp)
            + "; chmod 600 " + shellQuote(tmp)
            + "; mv " + shellQuote(tmp) + " " + shellQuote(file);
        Process process = new ProcessBuilder("wsl.exe", "-d", context.distribution, "-u", context.user,
            "--", "bash", "-lc", script)
            .redirectErrorStream(true)
            .start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(bytes);
        }
        byte[] output = readProcessOutput(process, 8);
        if (process.exitValue() != 0) {
            throw new IOException("Failed to write WSL Codex auth: " + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static Path getJetBrainsCodexHome() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            String home = System.getProperty("user.home", ".");
            return Path.of(home, ".local", "share", "JetBrains", productProfile(), "aia", "codex");
        }
        return Path.of(localAppData, "JetBrains", productProfile(), "aia", "codex");
    }

    private static String wslJetBrainsCodexHome(WslProjectContext context) {
        return context.homePath + "/.local/share/JetBrains/" + productProfile() + "/aia/codex";
    }

    private static String productProfile() {
        String selector = System.getProperty("idea.paths.selector");
        if (selector != null && !selector.isBlank()) {
            return selector;
        }

        return "unknown";
    }

    private static AcpAgentStartConfig newStartConfig(
        String command,
        List<String> baseArgs,
        List<String> acpArgs,
        Map<String, String> env
    ) {
        try {
            Constructor<AcpAgentStartConfig> constructor = AcpAgentStartConfig.class.getConstructor(
                String.class,
                List.class,
                List.class,
                Map.class
            );
            return constructor.newInstance(command, baseArgs, acpArgs, env);
        }
        catch (NoSuchMethodException ignored) {
            try {
                Constructor<AcpAgentStartConfig> constructor = AcpAgentStartConfig.class.getConstructor(
                    String.class,
                    List.class,
                    List.class,
                    Map.class,
                    Class.forName("kotlinx.serialization.json.JsonElement")
                );
                return constructor.newInstance(command, baseArgs, acpArgs, env, null);
            }
            catch (ReflectiveOperationException error) {
                return newStartConfigViaCompanion(command, baseArgs, acpArgs, env, error);
            }
        }
        catch (ReflectiveOperationException error) {
            return newStartConfigViaCompanion(command, baseArgs, acpArgs, env, error);
        }
    }

    private static AcpAgentStartConfig newStartConfigViaCompanion(
        String command,
        List<String> baseArgs,
        List<String> acpArgs,
        Map<String, String> env,
        ReflectiveOperationException constructorError
    ) {
        try {
            Object companion = AcpAgentStartConfig.class.getField("Companion").get(null);
            return (AcpAgentStartConfig) companion.getClass()
                .getMethod("create", String.class, List.class, List.class, Map.class)
                .invoke(companion, command, baseArgs, acpArgs, env);
        }
        catch (ReflectiveOperationException companionError) {
            companionError.addSuppressed(constructorError);
            throw new IllegalStateException("Failed to create AcpAgentStartConfig", companionError);
        }
    }

    private static Path getUserCodexHome() {
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile == null || userProfile.isBlank()) {
            return Path.of(System.getProperty("user.home", "."), ".codex");
        }
        return Path.of(userProfile, ".codex");
    }

    private static ProcessResult runProcess(int timeoutSeconds, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        byte[] output = readProcessOutput(process, timeoutSeconds);
        return new ProcessResult(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    private static byte[] readProcessOutput(Process process, int timeoutSeconds) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                process.getInputStream().transferTo(output);
            }
            catch (IOException ignored) {
            }
        }, "codex-auth-sync-output-reader");
        reader.setDaemon(true);
        reader.start();

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out waiting for process");
        }
        reader.join(TimeUnit.SECONDS.toMillis(1));
        return output.toByteArray();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        }
        catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static WslProjectContext detectWslProject(Path projectDir) {
        return projectDir == null ? null : detectWslProject(projectDir.toString());
    }

    private static WslProjectContext detectWslProject(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }

        String candidate = normalizePathCandidate(rawPath);
        String normalized = candidate.replace('/', '\\');
        String prefix = null;
        if (startsWithIgnoreCase(normalized, WSL_LOCALHOST_PREFIX)) {
            prefix = WSL_LOCALHOST_PREFIX;
        }
        else if (startsWithIgnoreCase(normalized, WSL_DOLLAR_PREFIX)) {
            prefix = WSL_DOLLAR_PREFIX;
        }

        if (prefix != null) {
            String rest = normalized.substring(prefix.length());
            int distroEnd = rest.indexOf('\\');
            if (distroEnd <= 0) {
                return null;
            }

            String distribution = rest.substring(0, distroEnd);
            String linuxPath = "/" + rest.substring(distroEnd + 1).replace('\\', '/');
            String user = extractHomeUser(linuxPath);
            return new WslProjectContext(distribution, user);
        }

        String unix = candidate.replace('\\', '/');
        WslProjectContext byPseudoUnc = detectPseudoUnc(unix);
        if (byPseudoUnc != null) {
            return byPseudoUnc;
        }
        if (unix.startsWith("/home/")) {
            String user = extractHomeUser(unix);
            return new WslProjectContext("", user);
        }

        return null;
    }

    private static String normalizePathCandidate(String rawPath) {
        String value = rawPath.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file:////")) {
            return "//" + value.substring("file:////".length());
        }
        if (lower.startsWith("file://")) {
            return "//" + value.substring("file://".length());
        }
        if (lower.startsWith("file:/")) {
            return value.substring("file:".length());
        }
        return value;
    }

    private static WslProjectContext detectPseudoUnc(String unixPath) {
        String value = unixPath;
        while (value.startsWith("//")) {
            value = value.substring(1);
        }

        String lower = value.toLowerCase(Locale.ROOT);
        String marker;
        if (lower.startsWith("/wsl.localhost/")) {
            marker = "/wsl.localhost/";
        }
        else if (lower.startsWith("/wsl$/")) {
            marker = "/wsl$/";
        }
        else {
            return null;
        }

        String rest = value.substring(marker.length());
        int distroEnd = rest.indexOf('/');
        if (distroEnd <= 0) {
            return null;
        }

        String distribution = rest.substring(0, distroEnd);
        String linuxPath = "/" + rest.substring(distroEnd + 1);
        String user = extractHomeUser(linuxPath);
        return new WslProjectContext(distribution, user);
    }

    private static String extractHomeUser(String linuxPath) {
        if (linuxPath == null || !linuxPath.startsWith("/home/")) {
            return null;
        }

        String remainder = linuxPath.substring("/home/".length());
        int slash = remainder.indexOf('/');
        String user = slash >= 0 ? remainder.substring(0, slash) : remainder;
        return user.isBlank() ? null : user;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isWindowsHost() {
        return java.io.File.separatorChar == '\\';
    }

    private static final class WslProjectContext {
        final String distribution;
        final String user;
        final String homePath;

        WslProjectContext(String distribution, String user) {
            this(distribution, user, user == null || user.isBlank() ? "" : "/home/" + user);
        }

        private WslProjectContext(String distribution, String user, String homePath) {
            this.distribution = distribution == null ? "" : distribution;
            this.user = user == null ? "" : user;
            this.homePath = homePath == null ? "" : homePath;
        }

        WslProjectContext withDefaults(Map<String, String> runtime) {
            String resolvedDistribution = firstNonBlank(distribution, runtime.get("WSL_DEFAULT_DISTRIBUTION"));
            if (resolvedDistribution == null) {
                return this;
            }
            String prefix = "WSL_" + configKey(resolvedDistribution) + "_";
            String resolvedUser = firstNonBlank(user, runtime.get(prefix + "USER"), runtime.get("WSL_DEFAULT_USER"));
            String resolvedHome = firstNonBlank(runtime.get(prefix + "HOME"), runtime.get("WSL_HOME"));
            if (resolvedHome == null && resolvedUser != null) {
                resolvedHome = "/home/" + resolvedUser;
            }
            return new WslProjectContext(resolvedDistribution, resolvedUser, resolvedHome);
        }
    }

    private record WslFileState(String path, boolean exists, long modifiedEpochSeconds, long size, String sha256) {
        static WslFileState missing(String path) {
            return new WslFileState(path, false, 0L, 0L, "");
        }
    }

    private record FileCandidate(Path localPath, String wslPath, long modifiedEpochSeconds) {
        byte[] readBytes(WslProjectContext context) throws Exception {
            if (localPath != null) {
                return Files.readAllBytes(localPath);
            }
            return readWslFile(context, wslPath);
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
