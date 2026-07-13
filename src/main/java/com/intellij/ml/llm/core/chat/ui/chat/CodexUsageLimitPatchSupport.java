package com.intellij.ml.llm.core.chat.ui.chat;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ml.llm.core.chat.ui.chat.input.AIAssistantInput;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.util.ui.JBUI;
import kotlinx.coroutines.flow.StateFlow;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodexUsageLimitPatchSupport {
    private static final String INSTALLED_KEY = "codex.usage.limit.patch.installed";
    private static final String BUTTON_KEY = "codex.usage.limit.patch.button";
    private static final String SNAPSHOT_FILE = "jetbrains-rate-limits.json";
    private static final long UI_REFRESH_MILLIS = 1_000L;
    private static final long STALE_AFTER_MILLIS = 75_000L;
    private static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;
    private static final DateTimeFormatter RESET_FORMAT =
        DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Codex Usage Limit UI");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Path, CachedRateLimitState> STATE_CACHE = new ConcurrentHashMap<>();

    private CodexUsageLimitPatchSupport() {}

    public static void install(JPanel feedbackPanel, JComponent feedbackLabel, AIAssistantInput input) {
        if (feedbackPanel == null || feedbackLabel == null) {
            return;
        }
        if (Boolean.TRUE.equals(feedbackPanel.getClientProperty(INSTALLED_KEY))) {
            return;
        }
        feedbackPanel.putClientProperty(INSTALLED_KEY, Boolean.TRUE);

        JButton limitButton = new JButton("--% \u25BE");
        limitButton.setFocusable(false);
        limitButton.setHorizontalAlignment(SwingConstants.CENTER);
        limitButton.setMargin(JBUI.insets(1, 6));
        limitButton.putClientProperty("JButton.buttonType", "toolBarButton");
        limitButton.putClientProperty(BUTTON_KEY, Boolean.TRUE);
        limitButton.getAccessibleContext().setAccessibleName("Codex usage limits");

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
            if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) == 0) {
                return;
            }
            if (limitButton.isDisplayable()) {
                controller.start();
            } else {
                controller.stop();
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
        private volatile Snapshot snapshot = Snapshot.unavailable("Waiting for Codex rate-limit data.");
        private volatile ScheduledFuture<?> future;

        private UsageButtonController(JButton button, AIAssistantInput input) {
            this.button = button;
            this.input = input;
        }

        private synchronized void start() {
            if (future != null && !future.isCancelled() && !future.isDone()) {
                return;
            }
            future = EXECUTOR.scheduleAtFixedRate(this::refreshSafely, 0L, UI_REFRESH_MILLIS, TimeUnit.MILLISECONDS);
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
                String selectedModel = detectSelectedModel(input);
                Snapshot next = loadSnapshot(selectedModel);
                snapshot = next;
                SwingUtilities.invokeLater(() -> applySnapshot(next));
            } catch (Throwable ignored) {
            }
        }

        private void applySnapshot(Snapshot next) {
            button.setText(next.buttonText());
            button.setToolTipText(next.tooltip());
        }

        private void showPopup() {
            Snapshot current = snapshot.refreshAge();
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(JBUI.Borders.empty(12, 14));

            JLabel title = new JLabel("Codex usage");
            title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1.0f));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(title);

            if (current.hasKnownModel()) {
                JLabel context = secondaryLabel(current.modelLabel() + "  \u00B7  " + current.bucketLabel());
                context.setBorder(JBUI.Borders.emptyTop(2));
                panel.add(context);
            }

            panel.add(Box.createVerticalStrut(JBUI.scale(10)));
            if (current.windows.isEmpty() || current.stale()) {
                String message = current.stale() && current.updatedAtMillis > 0L
                    ? "Waiting for a fresh Codex snapshot."
                    : current.error == null ? "Rate-limit data is unavailable." : current.error;
                JLabel unavailable = new JLabel(message);
                unavailable.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(unavailable);
            } else {
                for (int i = 0; i < current.windows.size(); i++) {
                    if (i > 0) {
                        panel.add(Box.createVerticalStrut(JBUI.scale(8)));
                    }
                    panel.add(createLimitCard(current.windows.get(i)));
                }
            }

            if (current.limitReached) {
                JLabel reached = new JLabel("This quota is exhausted.");
                reached.setForeground(errorColor());
                reached.setBorder(JBUI.Borders.emptyTop(9));
                reached.setAlignmentX(Component.LEFT_ALIGNMENT);
                panel.add(reached);
            }

            JLabel freshness = secondaryLabel(current.freshnessText());
            freshness.setBorder(JBUI.Borders.emptyTop(9));
            panel.add(freshness);

            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, panel)
                .setRequestFocus(false)
                .setResizable(false)
                .setMovable(false)
                .createPopup()
                .showUnderneathOf(button);
        }
    }

    private static JPanel createLimitCard(WindowSnapshot window) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor(), 1, true),
            JBUI.Borders.empty(8, 10)
        ));
        card.setMaximumSize(new Dimension(JBUI.scale(360), Integer.MAX_VALUE));
        card.setPreferredSize(new Dimension(JBUI.scale(330), JBUI.scale(72)));

        JPanel heading = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        heading.setOpaque(false);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = new JLabel(window.label());
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        JLabel remaining = new JLabel(percent(window.remainingPercent()) + " left");
        remaining.setFont(remaining.getFont().deriveFont(Font.BOLD));
        heading.add(name, BorderLayout.WEST);
        heading.add(remaining, BorderLayout.EAST);
        card.add(heading);
        card.add(Box.createVerticalStrut(JBUI.scale(6)));

        JProgressBar progress = new JProgressBar(0, 100);
        progress.setValue(Math.max(0, window.remainingPercent()));
        progress.setStringPainted(false);
        progress.setBorderPainted(false);
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(5)));
        progress.setPreferredSize(new Dimension(JBUI.scale(300), JBUI.scale(5)));
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(progress);
        card.add(Box.createVerticalStrut(JBUI.scale(5)));

        JLabel reset = secondaryLabel("Resets " + window.resetDateText() + "  \u00B7  in " + window.resetInText());
        card.add(reset);
        return card;
    }

    private static JLabel secondaryLabel(String text) {
        JLabel label = new JLabel(text);
        Color color = UIManager.getColor("Label.disabledForeground");
        if (color != null) {
            label.setForeground(color);
        }
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static Color borderColor() {
        Color color = UIManager.getColor("Component.borderColor");
        return color != null ? color : new Color(128, 128, 128, 90);
    }

    private static Color errorColor() {
        Color color = UIManager.getColor("ValidationTooltip.errorBackground");
        return color != null ? color.darker() : new Color(190, 55, 55);
    }

    private static Snapshot loadSnapshot(String selectedModel) {
        Path codexHome = activeCodexHome();
        if (codexHome == null) {
            return Snapshot.unavailable("The active Codex environment could not be resolved.").withModel(selectedModel);
        }
        Path snapshotPath = codexHome.resolve(SNAPSHOT_FILE);
        RateLimitState state = readRateLimitState(snapshotPath);
        if (state == null) {
            return Snapshot.unavailable("Waiting for the first app-server limit refresh.").withModel(selectedModel);
        }

        RateLimitBucket bucket = selectBucket(state.buckets, selectedModel);
        if (bucket == null) {
            return Snapshot.unavailable("Codex returned no rate-limit buckets.")
                .withModel(selectedModel)
                .withUpdatedAt(state.updatedAtMillis);
        }
        List<WindowSnapshot> windows = bucket.windows.stream()
            .sorted(Comparator.comparingInt(WindowSnapshot::windowMinutes))
            .toList();
        return new Snapshot(
            selectedModel,
            bucket,
            windows,
            state.updatedAtMillis,
            bucket.limitReached,
            null
        ).refreshAge();
    }

    private static RateLimitState readRateLimitState(Path path) {
        try {
            long modified = Files.getLastModifiedTime(path).toMillis();
            CachedRateLimitState cached = STATE_CACHE.get(path);
            if (cached != null && cached.modifiedMillis == modified) {
                return cached.state;
            }
            long size = Files.size(path);
            if (size <= 0L || size > MAX_SNAPSHOT_BYTES) {
                return null;
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            RateLimitState parsed = parseRateLimitState(json);
            if (parsed != null) {
                STATE_CACHE.put(path, new CachedRateLimitState(modified, parsed));
                return parsed;
            }
            return cached == null ? null : cached.state;
        } catch (IOException ignored) {
            CachedRateLimitState cached = STATE_CACHE.get(path);
            return cached == null ? null : cached.state;
        } catch (Throwable ignored) {
            CachedRateLimitState cached = STATE_CACHE.get(path);
            return cached == null ? null : cached.state;
        }
    }

    private static RateLimitState parseRateLimitState(String json) {
        Long updatedAt = parseLong(json, "updatedAt");
        String bucketsObject = objectForKey(json, "rateLimitsByLimitId");
        Map<String, RateLimitBucket> buckets = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : objectEntries(bucketsObject).entrySet()) {
            RateLimitBucket bucket = parseBucket(entry.getKey(), entry.getValue());
            if (bucket != null) {
                buckets.put(bucket.limitId, bucket);
            }
        }
        if (buckets.isEmpty()) {
            RateLimitBucket fallback = parseBucket("codex", objectForKey(json, "rateLimits"));
            if (fallback != null) {
                buckets.put(fallback.limitId, fallback);
            }
        }
        if (updatedAt == null || updatedAt <= 0L || buckets.isEmpty()) {
            return null;
        }
        return new RateLimitState(updatedAt, buckets);
    }

    static String summarizeForTest(String json, String selectedModel) {
        RateLimitState state = parseRateLimitState(json);
        if (state == null) {
            return "unavailable";
        }
        RateLimitBucket bucket = selectBucket(state.buckets, selectedModel);
        if (bucket == null) {
            return "unavailable";
        }
        String windows = bucket.windows.stream()
            .sorted(Comparator.comparingInt(WindowSnapshot::windowMinutes))
            .map(window -> window.windowMinutes + "=" + window.remainingPercent())
            .reduce((left, right) -> left + "," + right)
            .orElse("");
        return bucket.limitId + "|" + windows;
    }

    private static RateLimitBucket parseBucket(String mapKey, String object) {
        if (object == null) {
            return null;
        }
        String limitId = firstNonBlank(parseString(object, "limitId"), mapKey);
        String limitName = parseString(object, "limitName");
        List<WindowSnapshot> windows = new ArrayList<>();
        WindowSnapshot primary = parseWindow(objectForKey(object, "primary"));
        WindowSnapshot secondary = parseWindow(objectForKey(object, "secondary"));
        if (primary != null) {
            windows.add(primary);
        }
        if (secondary != null) {
            windows.add(secondary);
        }
        if (windows.isEmpty()) {
            return null;
        }
        String reachedType = parseString(object, "rateLimitReachedType");
        return new RateLimitBucket(limitId, limitName, windows, reachedType != null && !reachedType.isBlank());
    }

    private static WindowSnapshot parseWindow(String object) {
        if (object == null) {
            return null;
        }
        Double usedPercent = parseDouble(object, "usedPercent");
        Integer windowMinutes = parseInt(object, "windowDurationMins");
        Long resetsAt = parseLong(object, "resetsAt");
        if (usedPercent == null || windowMinutes == null) {
            return null;
        }
        return new WindowSnapshot(
            clamp((int)Math.round(usedPercent), 0, 100),
            windowMinutes,
            resetsAt == null ? -1L : resetsAt
        );
    }

    private static RateLimitBucket selectBucket(Map<String, RateLimitBucket> buckets, String selectedModel) {
        String normalizedModel = normalizeModel(selectedModel);
        if (!normalizedModel.isBlank()) {
            RateLimitBucket best = null;
            int bestScore = 0;
            for (RateLimitBucket bucket : buckets.values()) {
                String normalizedName = normalizeModel(bucket.limitName);
                if (normalizedName.isBlank()) {
                    continue;
                }
                int score = normalizedModel.equals(normalizedName) ? 100
                    : normalizedModel.contains(normalizedName) ? 80
                    : normalizedName.contains(normalizedModel) ? 60
                    : tokenOverlapScore(normalizedModel, normalizedName);
                if (score > bestScore) {
                    best = bucket;
                    bestScore = score;
                }
            }
            if (best != null && bestScore >= 40) {
                return best;
            }
        }
        RateLimitBucket defaultBucket = buckets.get("codex");
        if (defaultBucket != null) {
            return defaultBucket;
        }
        for (RateLimitBucket bucket : buckets.values()) {
            if (bucket.limitName == null || bucket.limitName.isBlank()) {
                return bucket;
            }
        }
        return buckets.values().stream().findFirst().orElse(null);
    }

    private static int tokenOverlapScore(String left, String right) {
        if (left.contains("spark") && right.contains("spark")) {
            return 50;
        }
        return 0;
    }

    private static String normalizeModel(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("\\[(?:low|medium|high|xhigh|max|ultra)]", "")
            .replaceAll("\\((?:low|medium|high|xhigh|max|ultra)\\)", "")
            .replaceAll("[^a-z0-9]", "");
    }

    private static String detectSelectedModel(AIAssistantInput input) {
        String fromVm = detectSelectedModelFromVm(input);
        if (isMeaningfulModel(fromVm)) {
            return fromVm;
        }
        String fromToolbar = detectSelectedModelFromToolbar(input);
        return isMeaningfulModel(fromToolbar) ? fromToolbar : "unknown";
    }

    private static boolean isMeaningfulModel(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("gpt") || normalized.contains("codex");
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
            List<String> texts = new ArrayList<>();
            collectTexts(input.getBottomToolbarPanel(), texts);
            for (String text : texts) {
                if (isMeaningfulModel(text)) {
                    return text;
                }
            }
        } catch (Throwable ignored) {
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

    private static void collectTexts(Component component, List<String> texts) {
        if (component == null) {
            return;
        }
        if (component instanceof JLabel label && label.getText() != null) {
            texts.add(label.getText());
        } else if (component instanceof JButton button && button.getText() != null) {
            texts.add(button.getText());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectTexts(child, texts);
            }
        }
    }

    private static Path activeCodexHome() {
        String localAppData = System.getenv("LOCALAPPDATA");
        String selector = System.getProperty("idea.paths.selector", "unknown");
        String distro = activeWslDistribution();
        if (isWindowsHost() && distro != null) {
            Map<String, String> runtime = loadRuntimeConfig(localAppData, selector);
            String prefix = "WSL_" + configKey(distro) + "_";
            String wslHome = firstNonBlank(runtime.get(prefix + "CODEX_HOME"), runtime.get("WSL_CODEX_HOME"));
            if (wslHome != null && wslHome.startsWith("/")) {
                return Path.of("\\\\wsl.localhost\\" + distro + wslHome.replace('/', '\\'));
            }
            return null;
        }
        if (localAppData == null || localAppData.isBlank()) {
            return null;
        }
        return Path.of(localAppData, "JetBrains", selector, "aia", "codex");
    }

    private static String activeWslDistribution() {
        try {
            Project project = ProjectUtil.getActiveProject();
            String path = project == null ? null : project.getBasePath();
            if (path == null || path.isBlank()) {
                return null;
            }
            String normalized = path.replace('\\', '/');
            String lower = normalized.toLowerCase(Locale.ROOT);
            String prefix = lower.startsWith("//wsl.localhost/")
                ? "//wsl.localhost/"
                : lower.startsWith("//wsl$/") ? "//wsl$/" : null;
            if (prefix == null) {
                return null;
            }
            String remainder = normalized.substring(prefix.length());
            int slash = remainder.indexOf('/');
            return slash > 0 ? remainder.substring(0, slash) : null;
        } catch (Throwable ignored) {
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
        } catch (IOException ignored) {
            return Map.of();
        }
    }

    private static Map<String, String> objectEntries(String object) {
        Map<String, String> result = new LinkedHashMap<>();
        if (object == null || object.length() < 2) {
            return result;
        }
        int index = 1;
        while (index < object.length() - 1) {
            index = skipWhitespaceAndCommas(object, index);
            if (index >= object.length() - 1 || object.charAt(index) != '"') {
                break;
            }
            int keyEnd = findStringEnd(object, index + 1);
            if (keyEnd < 0) {
                break;
            }
            String key = unescapeJsonString(object.substring(index + 1, keyEnd));
            int colon = object.indexOf(':', keyEnd + 1);
            if (colon < 0) {
                break;
            }
            int valueStart = skipWhitespace(object, colon + 1);
            if (valueStart >= object.length() || object.charAt(valueStart) != '{') {
                index = valueStart + 1;
                continue;
            }
            String value = extractBalancedObject(object, valueStart);
            if (value == null) {
                break;
            }
            result.put(key, value);
            index = valueStart + value.length();
        }
        return result;
    }

    private static String objectForKey(String json, String key) {
        if (json == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:").matcher(json);
        if (!matcher.find()) {
            return null;
        }
        int start = skipWhitespace(json, matcher.end());
        return start < json.length() && json.charAt(start) == '{' ? extractBalancedObject(json, start) : null;
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

    private static String parseString(String text, String key) {
        if (text == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(text);
        return matcher.find() ? unescapeJsonString(matcher.group(1)) : null;
    }

    private static Double parseDouble(String text, String key) {
        if (text == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : null;
    }

    private static Integer parseInt(String text, String key) {
        Long value = parseLong(text, key);
        return value == null ? null : value.intValue();
    }

    private static Long parseLong(String text, String key) {
        if (text == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+)").matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private static int skipWhitespaceAndCommas(String text, int index) {
        int next = index;
        while (next < text.length() && (Character.isWhitespace(text.charAt(next)) || text.charAt(next) == ',')) {
            next++;
        }
        return next;
    }

    private static int skipWhitespace(String text, int index) {
        int next = index;
        while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
            next++;
        }
        return next;
    }

    private static int findStringEnd(String text, int start) {
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescapeJsonString(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String configKey(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private static boolean isWindowsHost() {
        return java.io.File.separatorChar == '\\';
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record CachedRateLimitState(long modifiedMillis, RateLimitState state) {}

    private record RateLimitState(long updatedAtMillis, Map<String, RateLimitBucket> buckets) {}

    private record RateLimitBucket(
        String limitId,
        String limitName,
        List<WindowSnapshot> windows,
        boolean limitReached
    ) {
        private String label() {
            if (limitName != null && !limitName.isBlank()) {
                return limitName;
            }
            return Objects.equals(limitId, "codex") ? "Shared Codex quota" : limitId;
        }
    }

    private record WindowSnapshot(int usedPercent, int windowMinutes, long resetsAt) {
        private int remainingPercent() {
            return clamp(100 - usedPercent, 0, 100);
        }

        private String label() {
            if (windowMinutes == 300) {
                return "5 hours";
            }
            if (windowMinutes == 10080) {
                return "Weekly";
            }
            if (windowMinutes < 60) {
                return windowMinutes + " minutes";
            }
            if (windowMinutes < 1440) {
                return Math.round(windowMinutes / 60.0) + " hours";
            }
            return Math.round(windowMinutes / 1440.0) + " days";
        }

        private String resetDateText() {
            return resetsAt > 0L ? RESET_FORMAT.format(Instant.ofEpochSecond(resetsAt)) : "unknown";
        }

        private String resetInText() {
            return resetsAt > 0L ? formatDuration(Math.max(0L, resetsAt - Instant.now().getEpochSecond())) : "unknown";
        }
    }

    private record Snapshot(
        String selectedModel,
        RateLimitBucket bucket,
        List<WindowSnapshot> windows,
        long updatedAtMillis,
        boolean limitReached,
        String error
    ) {
        private static Snapshot unavailable(String error) {
            return new Snapshot("unknown", null, List.of(), -1L, false, error);
        }

        private Snapshot withModel(String model) {
            return new Snapshot(model == null || model.isBlank() ? selectedModel : model, bucket, windows, updatedAtMillis, limitReached, error);
        }

        private Snapshot withUpdatedAt(long nextUpdatedAt) {
            return new Snapshot(selectedModel, bucket, windows, nextUpdatedAt, limitReached, error);
        }

        private Snapshot refreshAge() {
            return this;
        }

        private boolean stale() {
            return updatedAtMillis <= 0L || System.currentTimeMillis() - updatedAtMillis > STALE_AFTER_MILLIS;
        }

        private String buttonText() {
            if (stale() || windows.isEmpty()) {
                return "--% \u25BE";
            }
            return windows.stream()
                .limit(2)
                .map(window -> percent(window.remainingPercent()))
                .reduce((left, right) -> left + " \u00B7 " + right)
                .orElse("--%") + " \u25BE";
        }

        private String tooltip() {
            if (stale()) {
                return "Codex limits are waiting for a fresh app-server snapshot.";
            }
            String limits = windows.stream()
                .map(window -> window.label() + ": " + percent(window.remainingPercent()) + " remaining")
                .reduce((left, right) -> left + " \u00B7 " + right)
                .orElse("No rate-limit windows");
            return limits + (hasKnownModel() ? " \u00B7 " + modelLabel() : "");
        }

        private boolean hasKnownModel() {
            return selectedModel != null && !selectedModel.isBlank() && !"unknown".equalsIgnoreCase(selectedModel);
        }

        private String modelLabel() {
            if (!hasKnownModel()) {
                return "Unknown model";
            }
            int open = selectedModel.indexOf(" (");
            return open > 0 ? selectedModel.substring(0, open) : selectedModel;
        }

        private String bucketLabel() {
            return bucket == null ? "No quota bucket" : bucket.label();
        }

        private String freshnessText() {
            if (updatedAtMillis <= 0L) {
                return "Waiting for Codex app-server";
            }
            long ageSeconds = Math.max(0L, (System.currentTimeMillis() - updatedAtMillis) / 1000L);
            if (stale()) {
                return "Data is stale (last update " + formatDuration(ageSeconds) + " ago)";
            }
            return ageSeconds < 2L ? "Updated just now" : "Updated " + ageSeconds + "s ago";
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
            return days + "d " + hours + "h";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        return Math.max(1L, minutes) + "m";
    }
}
