package com.intellij.ml.llm.chat.session;

import ai.grazie.utils.json.JSON;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ml.llm.chat.shared.ChatSessionEvent;
import com.intellij.ml.llm.core.BundledAgentsUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SessionHistoryUiCachePatchSupport {
    static final long CACHE_THRESHOLD_BYTES = 8L * 1024L * 1024L;
    static final int HOT_EVENT_WINDOW = 600;

    private static final String RAW_FORMAT = "AUI_EVENTS_V1";
    private static final String CACHE_FORMAT = "JBAI_UI_CACHE_V2";
    private static final String CACHE_SUFFIX = ".ui-cache-v2";
    private static final int TERMINAL_HEAD_CHARS = 32 * 1024;
    private static final int TERMINAL_TAIL_CHARS = 64 * 1024;
    private static final int RESULT_HEAD_CHARS = 128 * 1024;
    private static final int RESULT_TAIL_CHARS = 128 * 1024;
    private static final int TOOL_HEAD_CHARS = 32 * 1024;
    private static final int TOOL_TAIL_CHARS = 32 * 1024;
    private static final int CHANGE_HEAD_CHARS = 2 * 1024;
    private static final int CHANGE_TAIL_CHARS = 2 * 1024;
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile(
        "\"id\"\\s*:\\s*\\{\\s*\"id\"\\s*:\\s*(\\d+)"
    );
    private static final Logger LOG = Logger.getInstance(SessionHistoryUiCachePatchSupport.class);
    private static final Map<Path, Object> BUILD_LOCKS = new ConcurrentHashMap<>();
    private static final Set<Path> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();

    private SessionHistoryUiCachePatchSupport() {}

    public static List<ChatSessionEvent> getUiEvents(
        SessionHistoryStorage storage,
        SessionHistoryStorage.PersistanceId sessionId
    ) {
        if (storage == null || sessionId == null) {
            return List.of();
        }
        Path rawFile = rawFile(sessionId);
        try {
            if (!Files.isRegularFile(rawFile) || Files.size(rawFile) < CACHE_THRESHOLD_BYTES) {
                return storage.getEvents(sessionId);
            }
            Path cacheFile = ensureCurrentCache(rawFile);
            return readTypedEvents(cacheFile);
        } catch (Throwable error) {
            try {
                Files.deleteIfExists(cacheFile(rawFile));
            } catch (IOException ignored) {
            }
            if (LOGGED_FAILURES.add(rawFile)) {
                LOG.warn("Unable to use compact UI history cache for " + rawFile + "; using native history.", error);
            }
            return storage.getEvents(sessionId);
        }
    }

    public static void discardCache(SessionHistoryStorage.PersistanceId sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            Files.deleteIfExists(cacheFile(rawFile(sessionId)));
        } catch (IOException error) {
            LOG.warn("Unable to delete compact UI history cache for " + sessionId.getId(), error);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--prepare-all".equals(args[0])) {
            throw new IllegalArgumentException(
                "Usage: SessionHistoryUiCachePatchSupport --prepare-all <aia-task-history-directory>"
            );
        }
        CachePreparationSummary summary = prepareAll(Path.of(args[1]));
        System.out.println(
            "Prepared history UI caches: scanned=" + summary.scannedFiles
                + ", built=" + summary.builtFiles
                + ", reused=" + summary.reusedFiles
                + ", rawMiB=" + toMiB(summary.rawBytes)
                + ", cacheMiB=" + toMiB(summary.cacheBytes)
        );
    }

    static CachePreparationSummary prepareAll(Path historyDirectory) throws IOException {
        CachePreparationSummary summary = new CachePreparationSummary();
        if (!Files.isDirectory(historyDirectory)) {
            return summary;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(historyDirectory, "*.events")) {
            for (Path rawFile : files) {
                long rawSize = Files.size(rawFile);
                if (rawSize < CACHE_THRESHOLD_BYTES) {
                    continue;
                }
                summary.scannedFiles++;
                summary.rawBytes += rawSize;
                Path target = cacheFile(rawFile);
                if (isCurrent(target, rawSize, Files.getLastModifiedTime(rawFile).toMillis())) {
                    summary.reusedFiles++;
                } else {
                    ensureCurrentCache(rawFile);
                    summary.builtFiles++;
                }
                readTypedEvents(target);
                summary.cacheBytes += Files.size(target);
            }
        }
        return summary;
    }

    static Path buildCacheForTest(Path rawFile) throws IOException {
        return ensureCurrentCache(rawFile);
    }

    static List<String> readCachedJsonForTest(Path rawFile) throws IOException {
        return readEncodedJson(cacheFile(rawFile));
    }

    static Path cacheFileForTest(Path rawFile) {
        return cacheFile(rawFile);
    }

    private static Path rawFile(SessionHistoryStorage.PersistanceId sessionId) {
        return SessionHistoryStorageKt.getSessionHistoryDirectory().resolve(sessionId.getId() + ".events");
    }

    private static Path cacheFile(Path rawFile) {
        String fileName = rawFile.getFileName().toString();
        String sessionName = fileName.endsWith(".events")
            ? fileName.substring(0, fileName.length() - ".events".length())
            : fileName;
        return rawFile.resolveSibling(sessionName + CACHE_SUFFIX);
    }

    private static Path ensureCurrentCache(Path rawFile) throws IOException {
        Path normalized = rawFile.toAbsolutePath().normalize();
        Object lock = BUILD_LOCKS.computeIfAbsent(normalized, ignored -> new Object());
        synchronized (lock) {
            try {
                for (int attempt = 0; attempt < 2; attempt++) {
                    long rawSize = Files.size(normalized);
                    long rawModified = Files.getLastModifiedTime(normalized).toMillis();
                    Path target = cacheFile(normalized);
                    if (isCurrent(target, rawSize, rawModified)) {
                        return target;
                    }
                    if (buildCache(normalized, target, rawSize, rawModified)) {
                        LOGGED_FAILURES.remove(normalized);
                        return target;
                    }
                }
                throw new IOException("History changed while its UI cache was being built: " + normalized);
            } finally {
                BUILD_LOCKS.remove(normalized, lock);
            }
        }
    }

    private static boolean buildCache(
        Path rawFile,
        Path cacheFile,
        long expectedRawSize,
        long expectedRawModified
    ) throws IOException {
        RawScan scan = scanRawFile(rawFile);
        if (!scan.versionMarkerFound) {
            throw new IOException("Unsupported session history format: " + rawFile);
        }

        BuildData data = selectUiEvents(rawFile, scan);
        Path temporary = Files.createTempFile(
            cacheFile.getParent(),
            cacheFile.getFileName().toString() + ".",
            ".tmp"
        );
        boolean moved = false;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(
                temporary,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING
            )) {
                writer.write(cacheHeader(
                    expectedRawSize,
                    expectedRawModified,
                    scan.eventRecords,
                    data.events.size(),
                    data.omittedEvents
                ));
                writer.newLine();
                writer.write(RAW_FORMAT);
                writer.newLine();
                for (JsonObject event : data.events.values()) {
                    String json = event.toString();
                    writer.write(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
                    writer.newLine();
                }
            }

            long currentSize = Files.size(rawFile);
            long currentModified = Files.getLastModifiedTime(rawFile).toMillis();
            if (currentSize != expectedRawSize || currentModified != expectedRawModified) {
                return false;
            }
            moveReplacing(temporary, cacheFile);
            moved = true;
            return true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static RawScan scanRawFile(Path rawFile) throws IOException {
        Map<Integer, Long> latestLineByEventId = new HashMap<>();
        boolean markerFound = false;
        long eventLine = 0L;
        int maxEventId = -1;
        try (BufferedReader reader = Files.newBufferedReader(rawFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (RAW_FORMAT.equals(line)) {
                    markerFound = true;
                    continue;
                }
                if (line.isEmpty()) {
                    continue;
                }
                String json = decode(line);
                int eventId = eventId(json);
                if (eventId >= 0) {
                    latestLineByEventId.put(eventId, eventLine);
                    maxEventId = Math.max(maxEventId, eventId);
                }
                eventLine++;
            }
        }
        return new RawScan(markerFound, latestLineByEventId, maxEventId, eventLine);
    }

    private static BuildData selectUiEvents(Path rawFile, RawScan scan) throws IOException {
        TreeMap<Integer, JsonObject> retained = new TreeMap<>();
        JsonObject archiveMarkerSource = null;
        int omittedEvents = 0;
        long eventLine = 0L;
        int hotStart = Math.max(0, scan.maxEventId - HOT_EVENT_WINDOW + 1);
        String lastPromptAgentId = null;

        try (BufferedReader reader = Files.newBufferedReader(rawFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (RAW_FORMAT.equals(line) || line.isEmpty()) {
                    continue;
                }
                String json = decode(line);
                int eventId = eventId(json);
                Long latestLine = scan.latestLineByEventId.get(eventId);
                boolean latest = latestLine != null && latestLine == eventLine;
                eventLine++;
                if (eventId < 0 || (!latest && !looksLikeMarkdown(json))) {
                    continue;
                }

                JsonObject outer = JsonParser.parseString(json).getAsJsonObject();
                String outerType = string(outer, "type");
                String eventKind = nestedString(outer, "event", "kind");
                boolean userPrompt = outerType != null && outerType.endsWith("ChatSessionUserPromptEvent");
                boolean markdown = eventKind != null && eventKind.endsWith("MarkdownBlockUpdatedEvent");
                boolean result = eventKind != null && eventKind.endsWith("ResultBlockUpdatedEvent");
                if (userPrompt) {
                    String agentId = nestedString(outer, "agentId", "id");
                    if (agentId != null && !agentId.isBlank()) {
                        lastPromptAgentId = agentId;
                    }
                }

                if (!latest && markdown) {
                    mergeMarkdown(retained, eventId, outer);
                    continue;
                }

                boolean technicalBlock = eventKind != null;
                boolean keep = eventId >= hotStart || userPrompt || markdown || result || !technicalBlock;
                if (!keep) {
                    omittedEvents++;
                    if (archiveMarkerSource == null) {
                        archiveMarkerSource = outer.deepCopy();
                    }
                    continue;
                }

                JsonObject compact = compactForUi(outer, eventKind, rawFile.getFileName().toString());
                if (markdown) {
                    mergeMarkdown(retained, eventId, compact);
                } else {
                    retained.put(eventId, compact);
                }
            }
        }

        if (archiveMarkerSource != null) {
            int markerId = eventId(archiveMarkerSource);
            retained.put(markerId, historyMarker(archiveMarkerSource, rawFile, omittedEvents));
        }
        migrateAgentIds(retained, lastPromptAgentId);
        return new BuildData(retained, omittedEvents);
    }

    private static JsonObject compactForUi(JsonObject source, String kind, String rawFileName) {
        JsonObject copy = source.deepCopy();
        JsonObject event = object(copy, "event");
        if (event == null || kind == null) {
            return copy;
        }
        String note = "\n\n[UI cache shortened this payload; full data is preserved in "
            + rawFileName + ".]\n\n";
        if (kind.endsWith("TerminalBlockUpdatedEvent")) {
            truncateProperty(event, "output", TERMINAL_HEAD_CHARS, TERMINAL_TAIL_CHARS, note);
        } else if (kind.endsWith("ResultBlockUpdatedEvent")) {
            truncateProperty(event, "result", RESULT_HEAD_CHARS, RESULT_TAIL_CHARS, note);
            truncateTree(event.get("changes"), CHANGE_HEAD_CHARS, CHANGE_TAIL_CHARS, note);
        } else if (kind.endsWith("FileChangesBlockUpdatedEvent")) {
            truncateTree(event.get("changes"), CHANGE_HEAD_CHARS, CHANGE_TAIL_CHARS, note);
        } else if (kind.endsWith("McpBlockUpdatedEvent")) {
            truncateProperty(event, "arguments", TOOL_HEAD_CHARS, TOOL_TAIL_CHARS, note);
            truncateProperty(event, "details", TOOL_HEAD_CHARS, TOOL_TAIL_CHARS, note);
        } else if (kind.endsWith("ToolBlockUpdatedEvent")) {
            truncateProperty(event, "text", TOOL_HEAD_CHARS, TOOL_TAIL_CHARS, note);
        }
        return copy;
    }

    private static JsonObject historyMarker(JsonObject source, Path rawFile, int omittedEvents) {
        JsonObject marker = source.deepCopy();
        int markerId = eventId(marker);
        JsonObject event = new JsonObject();
        event.addProperty("kind", "com.intellij.ml.llm.aui.events.api.MarkdownBlockUpdatedEvent");
        event.addProperty("stepId", "jetbrains-history-ui-cache-" + markerId);
        event.addProperty(
            "textChunk",
            "Older technical tool details are not rendered to keep this long chat responsive.\n\n"
                + "All user prompts and assistant Markdown remain visible. "
                + omittedEvents + " old tool events remain unchanged in the append-only history:\n\n`"
                + rawFile.toAbsolutePath().normalize() + "`"
        );
        marker.add("event", event);
        return marker;
    }

    private static void mergeMarkdown(Map<Integer, JsonObject> retained, int eventId, JsonObject next) {
        JsonObject previous = retained.get(eventId);
        if (previous == null) {
            retained.put(eventId, next.deepCopy());
            return;
        }
        JsonObject previousEvent = object(previous, "event");
        JsonObject nextEvent = object(next, "event");
        if (previousEvent == null || nextEvent == null) {
            retained.put(eventId, next.deepCopy());
            return;
        }
        String previousText = string(previousEvent, "textChunk");
        String nextText = string(nextEvent, "textChunk");
        JsonObject merged = next.deepCopy();
        JsonObject mergedEvent = object(merged, "event");
        if (mergedEvent != null) {
            mergedEvent.addProperty(
                "textChunk",
                (previousText == null ? "" : previousText) + (nextText == null ? "" : nextText)
            );
        }
        retained.put(eventId, merged);
    }

    private static void migrateAgentIds(Map<Integer, JsonObject> events, String storedAgentId) {
        if (storedAgentId == null) {
            return;
        }
        String migrated = null;
        try {
            migrated = BundledAgentsUtils.INSTANCE.getMigratedAgentId(storedAgentId);
        } catch (Throwable ignored) {
            // Standalone cache preparation runs without an initialized IDE application.
        }
        String effective = migrated == null ? storedAgentId : migrated;
        for (JsonObject event : events.values()) {
            JsonObject agentId = object(event, "agentId");
            if (agentId != null) {
                agentId.addProperty("id", effective);
            }
        }
    }

    private static void truncateProperty(
        JsonObject object,
        String property,
        int headChars,
        int tailChars,
        String note
    ) {
        JsonElement value = object.get(property);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return;
        }
        String text = value.getAsString();
        if (text.length() > headChars + tailChars) {
            object.addProperty(property, text.substring(0, headChars) + note + text.substring(text.length() - tailChars));
        }
    }

    private static void truncateTree(
        JsonElement element,
        int headChars,
        int tailChars,
        String note
    ) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                JsonElement child = array.get(index);
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    String text = child.getAsString();
                    if (text.length() > headChars + tailChars) {
                        array.set(index, JsonParser.parseString(
                            quote(text.substring(0, headChars) + note + text.substring(text.length() - tailChars))
                        ));
                    }
                } else {
                    truncateTree(child, headChars, tailChars, note);
                }
            }
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : new ArrayList<>(object.entrySet())) {
                JsonElement child = entry.getValue();
                if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                    String text = child.getAsString();
                    if (text.length() > headChars + tailChars) {
                        object.addProperty(
                            entry.getKey(),
                            text.substring(0, headChars) + note + text.substring(text.length() - tailChars)
                        );
                    }
                } else {
                    truncateTree(child, headChars, tailChars, note);
                }
            }
        }
    }

    private static List<ChatSessionEvent> readTypedEvents(Path cacheFile) throws IOException {
        List<ChatSessionEvent> result = new ArrayList<>();
        for (String json : readEncodedJson(cacheFile)) {
            try {
                ChatSessionEvent event = (ChatSessionEvent) JSON.Default.INSTANCE.parse(
                    ChatSessionEvent.Companion.serializer(),
                    json
                );
                if (event != null) {
                    result.add(event);
                }
            } catch (Throwable error) {
                throw new IOException("Invalid cached history event in " + cacheFile, error);
            }
        }
        return result;
    }

    private static List<String> readEncodedJson(Path cacheFile) throws IOException {
        List<String> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null || !header.startsWith(CACHE_FORMAT + "\t")) {
                throw new IOException("Unsupported UI cache format: " + cacheFile);
            }
            String marker = reader.readLine();
            if (!RAW_FORMAT.equals(marker)) {
                throw new IOException("Missing UI cache event marker: " + cacheFile);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    result.add(decode(line));
                }
            }
        }
        return result;
    }

    private static boolean isCurrent(Path cacheFile, long rawSize, long rawModified) {
        if (!Files.isRegularFile(cacheFile)) {
            return false;
        }
        try (BufferedReader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            String[] fields = header == null ? new String[0] : header.split("\t");
            return fields.length >= 3
                && CACHE_FORMAT.equals(fields[0])
                && Long.parseLong(fields[1]) == rawSize
                && Long.parseLong(fields[2]) == rawModified;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String cacheHeader(
        long rawSize,
        long rawModified,
        long rawEvents,
        int uiEvents,
        int omittedEvents
    ) {
        return CACHE_FORMAT + "\t" + rawSize + "\t" + rawModified + "\t"
            + rawEvents + "\t" + uiEvents + "\t" + omittedEvents;
    }

    private static int eventId(String json) {
        Matcher matcher = EVENT_ID_PATTERN.matcher(json);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int eventId(JsonObject object) {
        JsonObject id = object(object, "id");
        if (id == null || !id.has("id")) {
            return -1;
        }
        try {
            return id.get("id").getAsInt();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean looksLikeMarkdown(String json) {
        return json.contains("MarkdownBlockUpdatedEvent");
    }

    private static JsonObject object(JsonObject parent, String property) {
        JsonElement value = parent.get(property);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String property) {
        JsonElement value = object.get(property);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String nestedString(JsonObject object, String parent, String property) {
        JsonObject nested = object(object, parent);
        return nested == null ? null : string(nested, property);
    }

    private static String decode(String encoded) throws IOException {
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid base64 session history record", error);
        }
    }

    private static String quote(String value) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("value", value);
        return wrapper.get("value").toString();
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long toMiB(long bytes) {
        return Math.round(bytes / 1024.0 / 1024.0);
    }

    private static final class RawScan {
        private final boolean versionMarkerFound;
        private final Map<Integer, Long> latestLineByEventId;
        private final int maxEventId;
        private final long eventRecords;

        private RawScan(
            boolean versionMarkerFound,
            Map<Integer, Long> latestLineByEventId,
            int maxEventId,
            long eventRecords
        ) {
            this.versionMarkerFound = versionMarkerFound;
            this.latestLineByEventId = latestLineByEventId;
            this.maxEventId = maxEventId;
            this.eventRecords = eventRecords;
        }
    }

    private static final class BuildData {
        private final TreeMap<Integer, JsonObject> events;
        private final int omittedEvents;

        private BuildData(TreeMap<Integer, JsonObject> events, int omittedEvents) {
            this.events = events;
            this.omittedEvents = omittedEvents;
        }
    }

    static final class CachePreparationSummary {
        private int scannedFiles;
        private int builtFiles;
        private int reusedFiles;
        private long rawBytes;
        private long cacheBytes;
    }
}
