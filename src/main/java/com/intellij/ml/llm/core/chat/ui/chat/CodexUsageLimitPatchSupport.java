package com.intellij.ml.llm.core.chat.ui.chat;

import com.intellij.ml.llm.core.chat.ui.chat.input.AIAssistantInput;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.project.Project;
import com.intellij.util.ui.JBUI;
import kotlinx.coroutines.flow.StateFlow;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodexUsageLimitPatchSupport {
    private static final String INSTALLED_KEY = "codex.usage.limit.patch.installed";
    private static final String BUTTON_KEY = "codex.usage.limit.patch.button";
    private static final String SPARK_KEY = "GPT-5.3-Codex-Spark";
    private static final long REFRESH_MILLIS = 20_000L;
    private static final int MAX_READ_BYTES = 8 * 1024 * 1024;
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Codex Usage Limit Patch");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, RateLimitEvent> LAST_RATE_LIMIT_EVENTS = new ConcurrentHashMap<>();

    private CodexUsageLimitPatchSupport() {}

    public static void install(JPanel feedbackPanel, JComponent feedbackLabel, AIAssistantInput input) {
        if (feedbackPanel == null || feedbackLabel == null) {
            return;
        }
        if (Boolean.TRUE.equals(feedbackPanel.getClientProperty(INSTALLED_KEY))) {
            return;
        }
        feedbackPanel.putClientProperty(INSTALLED_KEY, Boolean.TRUE);

        JButton limitButton = new JButton("--% / --% \u25BE");
        limitButton.setFocusable(false);
        limitButton.setOpaque(false);
        limitButton.setBorderPainted(false);
        limitButton.setContentAreaFilled(false);
        limitButton.setMargin(JBUI.insets(1, 4));
        limitButton.putClientProperty("JButton.buttonType", "toolBarButton");
        limitButton.putClientProperty(BUTTON_KEY, Boolean.TRUE);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
        rightPanel.setOpaque(false);
        rightPanel.add(limitButton);

        Container oldParent = feedbackLabel.getParent();
        if (oldParent != null) {
            oldParent.remove(feedbackLabel);
        }
        rightPanel.add(feedbackLabel);

        if (feedbackPanel.getLayout() instanceof BorderLayout) {
            feedbackPanel.add(rightPanel, BorderLayout.EAST);
        } else {
            feedbackPanel.add(rightPanel);
        }

        UsageButtonController controller = new UsageButtonController(limitButton, input);
        limitButton.addActionListener(event -> controller.showPopup());
        limitButton.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                if (limitButton.isDisplayable()) {
                    controller.start();
                } else {
                    controller.stop();
                }
            }
        });
        controller.refreshOnce();
        if (limitButton.isDisplayable()) {
            controller.start();
        }

        feedbackPanel.revalidate();
        feedbackPanel.repaint();
    }

    private static final class UsageButtonController {
        private final JButton button;
        private final AIAssistantInput input;
        private volatile Snapshot snapshot = Snapshot.unavailable("No Codex rate limit event found yet.");
        private volatile ScheduledFuture<?> future;

        private UsageButtonController(JButton button, AIAssistantInput input) {
            this.button = button;
            this.input = input;
        }

        private synchronized void start() {
            if (future != null && !future.isCancelled() && !future.isDone()) {
                return;
            }
            future = EXECUTOR.scheduleAtFixedRate(this::refreshSafely, 0L, REFRESH_MILLIS, TimeUnit.MILLISECONDS);
        }

        private synchronized void stop() {
            if (future != null) {
                future.cancel(false);
                future = null;
            }
        }

        private void refreshOnce() {
            EXECUTOR.execute(this::refreshSafely);
        }

        private void refreshSafely() {
            try {
                refresh();
            } catch (Throwable ignored) {
            }
        }

        private void refresh() {
            String selectedModel = detectSelectedModel(input);
            Snapshot next = loadSnapshot(selectedModel);
            snapshot = next;
            SwingUtilities.invokeLater(() -> applySnapshot(next));
        }

        private void applySnapshot(Snapshot next) {
            button.setText(next.buttonText());
            button.setToolTipText(next.tooltip());
        }

        private void showPopup() {
            Snapshot current = snapshot.withFreshCountdowns();
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(JBUI.Borders.empty(10, 12));
            int row = 0;
            addTitleRow(panel, row++, "Codex limits - " + current.bucketTitle());
            if (current.hasKnownModel()) {
                addMessageRow(panel, row++, "Model: " + current.selectedModel);
            }
            addHeaderRow(panel, row++);
            addLimitRow(panel, row++, "5 hours", current.primary);
            addLimitRow(panel, row++, "Week", current.secondary);
            if (current.limitReached || !current.allowed) {
                addMessageRow(panel, row++, "Limit reached for this bucket.");
            }
            if (current.error != null && !current.error.isBlank()) {
                addMessageRow(panel, row++, current.error);
            }
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, panel)
                .setRequestFocus(false)
                .setResizable(false)
                .setMovable(false)
                .createPopup()
                .showUnderneathOf(button);
        }
    }

    private static void addTitleRow(JPanel panel, int row, String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        GridBagConstraints constraints = baseConstraints(row, 0);
        constraints.gridwidth = 4;
        constraints.insets = JBUI.insets(0, 0, 8, 0);
        panel.add(label, constraints);
    }

    private static void addHeaderRow(JPanel panel, int row) {
        addCell(panel, row, 0, "", true);
        addCell(panel, row, 1, "Remaining", true);
        addCell(panel, row, 2, "Resets at", true);
        addCell(panel, row, 3, "In", true);
    }

    private static void addLimitRow(JPanel panel, int row, String name, WindowSnapshot window) {
        addCell(panel, row, 0, name, true);
        addCell(panel, row, 1, percent(window.remainingPercent()), false);
        addCell(panel, row, 2, window.resetDateText(), false);
        addCell(panel, row, 3, window.resetInText(), false);
    }

    private static void addMessageRow(JPanel panel, int row, String text) {
        GridBagConstraints constraints = baseConstraints(row, 0);
        constraints.gridwidth = 4;
        constraints.insets = JBUI.insets(7, 0, 0, 0);
        panel.add(new JLabel(text), constraints);
    }

    private static void addCell(JPanel panel, int row, int column, String text, boolean bold) {
        JLabel label = new JLabel(text);
        if (bold) {
            label.setFont(label.getFont().deriveFont(Font.BOLD));
        }
        GridBagConstraints constraints = baseConstraints(row, column);
        constraints.insets = JBUI.insets(2, column == 0 ? 0 : 12, 2, 0);
        panel.add(label, constraints);
    }

    private static GridBagConstraints baseConstraints(int row, int column) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = column == 1 ? 1.0 : 0.0;
        return constraints;
    }

    private static Snapshot loadSnapshot(String selectedModel) {
        RateLimitEvent event = cachedOrLatestRateLimitEvent();
        if (event == null) {
            return Snapshot.unavailable("No Codex rate limit event found yet. Send one request first.")
                .withModel(selectedModel);
        }

        boolean spark = isSparkModel(selectedModel);
        RateLimitBucket bucket = spark ? event.spark : event.standard;
        if (bucket == null) {
            String bucketName = spark ? "GPT-5.3 Codex Spark" : "default";
            return Snapshot.unavailable("No " + bucketName + " rate limit data from IDEA/WSL runtime yet.")
                .withModel(selectedModel)
                .withBucket(bucketName)
                .withSource(event.source);
        }

        return new Snapshot(
            selectedModel == null || selectedModel.isBlank() ? "unknown" : selectedModel,
            spark ? "GPT-5.3 Codex Spark" : "default",
            bucket.allowed,
            bucket.limitReached,
            bucket.primary,
            bucket.secondary,
            event.source,
            null
        ).withFreshCountdowns();
    }

    private static boolean isSparkModel(String selectedModel) {
        if (selectedModel == null) {
            return false;
        }
        String normalized = selectedModel.toLowerCase(Locale.ROOT);
        return normalized.contains("spark");
    }

    private static String detectSelectedModel(AIAssistantInput input) {
        String fromVm = detectSelectedModelFromVm(input);
        if (isMeaningfulModel(fromVm)) {
            return fromVm;
        }
        String fromToolbar = detectSelectedModelFromToolbar(input);
        if (isMeaningfulModel(fromToolbar)) {
            return fromToolbar;
        }
        return "unknown";
    }

    private static boolean isMeaningfulModel(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("spark") || normalized.contains("gpt") || normalized.contains("codex");
    }

    private static String detectSelectedModelFromVm(AIAssistantInput input) {
        if (input == null) {
            return null;
        }
        try {
            Method getModelSelectorVm = input.getClass().getMethod("getModelSelectorVm");
            Object vm = getModelSelectorVm.invoke(input);
            if (vm == null) {
                return null;
            }
            Object flow = vm.getClass().getMethod("getSelectedModel").invoke(vm);
            Object model = flow instanceof StateFlow<?> stateFlow
                ? stateFlow.getValue()
                : flow.getClass().getMethod("getValue").invoke(flow);
            if (model == null) {
                return null;
            }
            String id = invokeStringGetter(model, "getId");
            String displayName = invokeStringGetter(model, "getDisplayName");
            if (id != null && displayName != null && !id.equals(displayName)) {
                return displayName + " (" + id + ")";
            }
            return firstNonBlank(displayName, id, model.toString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String detectSelectedModelFromToolbar(AIAssistantInput input) {
        if (input == null) {
            return null;
        }
        try {
            JComponent toolbar = input.getBottomToolbarPanel();
            List<String> texts = new ArrayList<>();
            collectTexts(toolbar, texts);
            for (String text : texts) {
                String normalized = text.toLowerCase(Locale.ROOT);
                if (normalized.contains("spark") || normalized.contains("gpt") || normalized.contains("codex")) {
                    return text;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static String invokeStringGetter(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void collectTexts(Component component, List<String> texts) {
        if (component == null) {
            return;
        }
        if (component instanceof JButton button) {
            addText(texts, button.getText());
        } else if (component instanceof JLabel label) {
            addText(texts, label.getText());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectTexts(child, texts);
            }
        }
    }

    private static void addText(List<String> texts, String text) {
        if (text != null && !text.isBlank()) {
            texts.add(text.trim());
        }
    }

    private static RateLimitEvent cachedOrLatestRateLimitEvent() {
        String cacheKey = activeTelemetryKey();
        RateLimitEvent latest = findLatestRateLimitEvent();
        RateLimitEvent cached = LAST_RATE_LIMIT_EVENTS.get(cacheKey);
        if (latest == null) {
            return cached;
        }
        if (isNewerEvent(latest, cached)) {
            LAST_RATE_LIMIT_EVENTS.put(cacheKey, latest);
            return latest;
        }
        return cached;
    }

    private static String activeTelemetryKey() {
        String distribution = activeWslDistribution();
        return distribution == null || distribution.isBlank()
            ? "windows"
            : "wsl:" + distribution.toLowerCase(Locale.ROOT);
    }

    private static RateLimitEvent findLatestRateLimitEvent() {
        List<LogCandidate> candidates = logCandidates();
        candidates.sort(Comparator.comparingLong((LogCandidate candidate) -> candidate.modifiedMillis).reversed());
        RateLimitEvent latest = null;

        for (LogCandidate candidate : candidates) {
            String text = readTail(candidate.path);
            if (text == null || text.isBlank()) {
                continue;
            }
            RateLimitEvent event = latestRateLimitEvent(text, candidate.path.toString());
            if (event == null) {
                continue;
            }
            event.modifiedMillis = candidate.modifiedMillis;
            if (isNewerEvent(event, latest)) {
                latest = event;
            }
        }

        return latest;
    }

    private static boolean isNewerEvent(RateLimitEvent event, RateLimitEvent current) {
        if (event == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        if (event.eventTimestampMillis > 0L && current.eventTimestampMillis > 0L) {
            if (event.eventTimestampMillis != current.eventTimestampMillis) {
                return event.eventTimestampMillis > current.eventTimestampMillis;
            }
        }
        if (event.modifiedMillis > 0L || current.modifiedMillis > 0L) {
            if (event.modifiedMillis != current.modifiedMillis) {
                return event.modifiedMillis > current.modifiedMillis;
            }
        }
        if (Objects.equals(event.source, current.source) && event.sourceOffset != current.sourceOffset) {
            return event.sourceOffset > current.sourceOffset;
        }
        return event.sourceOffset >= current.sourceOffset;
    }

    private static List<LogCandidate> logCandidates() {
        List<Path> paths = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        String selector = System.getProperty("idea.paths.selector", "unknown");
        String distro = activeWslDistribution();
        if (isWindowsHost() && distro != null) {
            Map<String, String> runtime = loadRuntimeConfig(localAppData, selector);
            String prefix = "WSL_" + configKey(distro) + "_";
            String wslHome = firstNonBlank(runtime.get(prefix + "CODEX_HOME"), runtime.get("WSL_CODEX_HOME"));
            if (wslHome != null && wslHome.startsWith("/")) {
                addLogPaths(paths, Path.of("\\\\wsl.localhost\\" + distro + wslHome.replace('/', '\\')));
            }
        }
        else if (localAppData != null && !localAppData.isBlank()) {
            addLogPaths(paths, Path.of(localAppData, "JetBrains", selector, "aia", "codex"));
        }

        List<LogCandidate> result = new ArrayList<>();
        for (Path path : paths) {
            try {
                if (Files.isRegularFile(path)) {
                    result.add(new LogCandidate(path, Files.getLastModifiedTime(path).toMillis()));
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static void addLogPaths(List<Path> paths, Path codexHome) {
        paths.add(codexHome.resolve("logs_2.sqlite-wal"));
        paths.add(codexHome.resolve("logs_2.sqlite"));
        paths.add(codexHome.resolve("logs").resolve("logs_2.sqlite-wal"));
        paths.add(codexHome.resolve("logs").resolve("logs_2.sqlite"));
    }

    private static String activeWslDistribution() {
        try {
            Project project = ProjectUtil.getActiveProject();
            String path = project == null ? null : project.getBasePath();
            if (path == null || path.isBlank()) {
                return null;
            }
            String normalized = path.replace('\\', '/');
            String prefix = normalized.toLowerCase(Locale.ROOT).startsWith("//wsl.localhost/")
                ? "//wsl.localhost/"
                : normalized.toLowerCase(Locale.ROOT).startsWith("//wsl$/") ? "//wsl$/" : null;
            if (prefix == null) {
                return null;
            }
            String remainder = normalized.substring(prefix.length());
            int slash = remainder.indexOf('/');
            return slash > 0 ? remainder.substring(0, slash) : null;
        }
        catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, String> loadRuntimeConfig(String localAppData, String selector) {
        if (localAppData == null || localAppData.isBlank()) {
            return Map.of();
        }
        Path path = Path.of(localAppData, "JetBrains", selector, "aia", "codex", "jetbrains-ai-wsl-patch.env");
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            Map<String, String> result = new LinkedHashMap<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                if (equals > 0) {
                    result.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
                }
            }
            return result;
        }
        catch (IOException ignored) {
            return Map.of();
        }
    }

    private static String configKey(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static String readTail(Path path) {
        return readTail(path, MAX_READ_BYTES);
    }

    private static String readTail(Path path, int maxBytes) {
        try {
            long size = Files.size(path);
            int readLimit = Math.max(1, maxBytes);
            int length = (int) Math.min(size, readLimit);
            byte[] bytes;
            if (size <= readLimit) {
                bytes = Files.readAllBytes(path);
            } else {
                try (var in = Files.newInputStream(path)) {
                    long skipped = in.skip(size - length);
                    while (skipped < size - length) {
                        long next = in.skip(size - length - skipped);
                        if (next <= 0) {
                            break;
                        }
                        skipped += next;
                    }
                    bytes = in.readNBytes(length);
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static RateLimitEvent latestRateLimitEvent(String text, String source) {
        int marker = text.indexOf("codex.rate_limits");
        RateLimitEvent latest = null;
        while (marker >= 0) {
            int start = text.lastIndexOf('{', marker);
            while (start >= 0) {
                String json = extractBalancedObject(text, start);
                if (json != null && json.contains("\"rate_limits\"")) {
                    RateLimitEvent event = parseRateLimitEvent(json, source, parseEventTimestampMillis(text, marker), marker);
                    if (isNewerEvent(event, latest)) {
                        latest = event;
                    }
                    break;
                }
                start = text.lastIndexOf('{', start - 1);
            }
            marker = text.indexOf("codex.rate_limits", marker + "codex.rate_limits".length());
        }
        return latest;
    }

    private static long parseEventTimestampMillis(String text, int marker) {
        long fromAfter = parseEventTimestampMillisFromRange(text, marker, Math.min(text.length(), marker + 4096), true);
        if (fromAfter > 0L) {
            return fromAfter;
        }
        return parseEventTimestampMillisFromRange(text, Math.max(0, marker - 4096), marker, false);
    }

    private static long parseEventTimestampMillisFromRange(String text, int start, int end, boolean first) {
        if (end <= start || start < 0 || start >= text.length()) {
            return -1L;
        }
        String range = text.substring(start, Math.min(end, text.length()));
        Matcher matcher = Pattern.compile("event\\.timestamp=(\\d{4}-\\d{2}-\\d{2}T[^\\s\\x00]+)").matcher(range);
        long result = -1L;
        while (matcher.find()) {
            try {
                long parsed = Instant.parse(matcher.group(1)).toEpochMilli();
                if (first) {
                    return parsed;
                }
                result = parsed;
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static String extractBalancedObject(String text, int start) {
        boolean inString = false;
        boolean escape = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static RateLimitEvent parseRateLimitEvent(String json, String source, long eventTimestampMillis, long sourceOffset) {
        String standardObject = objectForKey(json, "rate_limits");
        RateLimitBucket standard = parseBucket(standardObject);
        String additionalObject = objectForKey(json, "additional_rate_limits");
        RateLimitBucket spark = parseBucket(objectForKey(additionalObject, SPARK_KEY));
        if (standard == null && spark == null) {
            return null;
        }
        long freshness = Math.max(freshnessSeconds(standard), freshnessSeconds(spark));
        return new RateLimitEvent(standard, spark, source, freshness, eventTimestampMillis, sourceOffset);
    }

    private static long freshnessSeconds(RateLimitBucket bucket) {
        if (bucket == null) {
            return -1L;
        }
        return Math.max(bucket.primary.resetAt, bucket.secondary.resetAt);
    }

    private static RateLimitBucket parseBucket(String object) {
        if (object == null) {
            return null;
        }
        WindowSnapshot primary = parseWindow(objectForKey(object, "primary"));
        WindowSnapshot secondary = parseWindow(objectForKey(object, "secondary"));
        if (primary == null && secondary == null) {
            return null;
        }
        return new RateLimitBucket(
            parseBoolean(object, "allowed", true),
            parseBoolean(object, "limit_reached", false),
            primary == null ? WindowSnapshot.unknown(300) : primary,
            secondary == null ? WindowSnapshot.unknown(10080) : secondary
        );
    }

    private static WindowSnapshot parseWindow(String object) {
        if (object == null) {
            return null;
        }
        Integer usedPercent = parseInt(object, "used_percent");
        Integer windowMinutes = parseInt(object, "window_minutes");
        Long resetAfterSeconds = parseLong(object, "reset_after_seconds");
        Long resetAt = parseLong(object, "reset_at");
        if (usedPercent == null && resetAt == null && resetAfterSeconds == null) {
            return null;
        }
        return new WindowSnapshot(
            usedPercent == null ? -1 : clamp(usedPercent, 0, 100),
            windowMinutes == null ? -1 : windowMinutes,
            resetAfterSeconds == null ? -1L : resetAfterSeconds,
            resetAt == null ? -1L : resetAt
        ).withFreshCountdown();
    }

    private static String objectForKey(String json, String key) {
        if (json == null) {
            return null;
        }
        int keyIndex = json.indexOf('"' + key + '"');
        if (keyIndex < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyIndex);
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('{', colon);
        if (start < 0) {
            return null;
        }
        return extractBalancedObject(json, start);
    }

    private static Integer parseInt(String text, String key) {
        Long value = parseLong(text, key);
        return value == null ? null : value.intValue();
    }

    private static Long parseLong(String text, String key) {
        if (text == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private static boolean parseBoolean(String text, String key, boolean fallback) {
        if (text == null) {
            return fallback;
        }
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)").matcher(text);
        return matcher.find() ? Boolean.parseBoolean(matcher.group(1)) : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isWindowsHost() {
        return java.io.File.separatorChar == '\\';
    }

    private record LogCandidate(Path path, long modifiedMillis) {}

    private static final class RateLimitEvent {
        private final RateLimitBucket standard;
        private final RateLimitBucket spark;
        private final String source;
        private final long freshnessSeconds;
        private final long eventTimestampMillis;
        private final long sourceOffset;
        private long modifiedMillis;

        private RateLimitEvent(
            RateLimitBucket standard,
            RateLimitBucket spark,
            String source,
            long freshnessSeconds,
            long eventTimestampMillis,
            long sourceOffset
        ) {
            this.standard = standard;
            this.spark = spark;
            this.source = source;
            this.freshnessSeconds = freshnessSeconds;
            this.eventTimestampMillis = eventTimestampMillis;
            this.sourceOffset = sourceOffset;
        }
    }

    private record RateLimitBucket(
        boolean allowed,
        boolean limitReached,
        WindowSnapshot primary,
        WindowSnapshot secondary
    ) {}

    private record WindowSnapshot(
        int usedPercent,
        int windowMinutes,
        long resetAfterSeconds,
        long resetAt
    ) {
        private static WindowSnapshot unknown(int windowMinutes) {
            return new WindowSnapshot(-1, windowMinutes, -1L, -1L);
        }

        private WindowSnapshot withFreshCountdown() {
            if (resetAt <= 0L) {
                return this;
            }
            long next = Math.max(0L, resetAt - Instant.now().getEpochSecond());
            return new WindowSnapshot(usedPercent, windowMinutes, next, resetAt);
        }

        private int remainingPercent() {
            return usedPercent < 0 ? -1 : clamp(100 - usedPercent, 0, 100);
        }

        private String resetDateText() {
            return resetAt > 0L ? DATE_FORMAT.format(Instant.ofEpochSecond(resetAt)) : "unknown";
        }

        private String resetInText() {
            return resetAfterSeconds >= 0L ? formatDuration(resetAfterSeconds) : "unknown";
        }
    }

    private record Snapshot(
        String selectedModel,
        String bucketName,
        boolean allowed,
        boolean limitReached,
        WindowSnapshot primary,
        WindowSnapshot secondary,
        String source,
        String error
    ) {
        private static Snapshot unavailable(String error) {
            return new Snapshot(
                "unknown",
                "unknown",
                false,
                false,
                WindowSnapshot.unknown(300),
                WindowSnapshot.unknown(10080),
                null,
                error
            );
        }

        private Snapshot withModel(String model) {
            return new Snapshot(
                model == null || model.isBlank() ? selectedModel : model,
                bucketName,
                allowed,
                limitReached,
                primary,
                secondary,
                source,
                error
            );
        }

        private Snapshot withSource(String nextSource) {
            return new Snapshot(selectedModel, bucketName, allowed, limitReached, primary, secondary, nextSource, error);
        }

        private Snapshot withBucket(String nextBucketName) {
            return new Snapshot(selectedModel, nextBucketName, allowed, limitReached, primary, secondary, source, error);
        }

        private Snapshot withFreshCountdowns() {
            return new Snapshot(
                selectedModel,
                bucketName,
                allowed,
                limitReached,
                primary.withFreshCountdown(),
                secondary.withFreshCountdown(),
                source,
                error
            );
        }

        private String buttonText() {
            String prefix = Objects.equals(bucketName, "GPT-5.3 Codex Spark") ? "Spark " : "";
            return prefix + percent(primary.remainingPercent()) + " / " + percent(secondary.remainingPercent()) + " \u25BE";
        }

        private String tooltip() {
            return "Codex limits, remaining 5h/week: " + buttonText();
        }

        private boolean hasKnownModel() {
            return selectedModel != null && !selectedModel.isBlank() && !"unknown".equalsIgnoreCase(selectedModel);
        }

        private String bucketTitle() {
            return Objects.equals(bucketName, "GPT-5.3 Codex Spark") ? "GPT-5.3 Spark" : "Default";
        }

    }

    private static String percent(int value) {
        return value < 0 ? "--%" : value + "%";
    }

    private static String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        long days = safe / 86_400L;
        safe %= 86_400L;
        long hours = safe / 3_600L;
        safe %= 3_600L;
        long minutes = safe / 60L;
        if (days > 0L) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
