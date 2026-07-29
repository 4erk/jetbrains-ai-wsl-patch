package com.intellij.ml.llm.chat.session;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class SessionHistoryUiCachePatchSupportTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("jetbrains-history-ui-cache-test");
        Path raw = directory.resolve("test-session.events");
        try {
            List<String> records = new ArrayList<>();
            records.add(userPrompt(1, "keep prompt"));
            records.add(markdown(2, "first "));
            records.add(markdown(2, "second"));
            records.add(terminal(3, "old terminal detail"));
            records.add(result(4, "keep result", "x".repeat(20_000)));
            for (int id = 5; id <= 698; id++) {
                records.add(thought(id, id == 5 ? "omitted thought sentinel" : "old thought " + id));
            }
            records.add(terminal(699, "obsolete output"));
            records.add(terminal(699, "H".repeat(70_000) + "T".repeat(70_000)));
            records.add(markdown(700, "latest answer"));
            writeRaw(raw, records);

            byte[] rawHash = sha256(raw);
            Path cache = SessionHistoryUiCachePatchSupport.buildCacheForTest(raw);
            assertArrayEquals(rawHash, sha256(raw), "The source history changed while building its UI cache.");
            assertTrue(Files.size(cache) < Files.size(raw), "The UI cache was not smaller than the source history.");

            List<JsonObject> cached = SessionHistoryUiCachePatchSupport.readCachedJsonForTest(raw).stream()
                .map(json -> JsonParser.parseString(json).getAsJsonObject())
                .toList();
            assertContainsText(cached, "prompt", "keep prompt");
            assertContainsText(cached, "textChunk", "first second");
            assertContainsText(cached, "result", "keep result");
            assertContainsText(cached, "textChunk", "Older technical tool details");
            assertNotContainsText(cached, "text", "omitted thought sentinel");
            assertNotContainsText(cached, "output", "obsolete output");

            JsonObject currentTerminal = eventById(cached, 699);
            String compactOutput = currentTerminal.getAsJsonObject("event").get("output").getAsString();
            assertTrue(compactOutput.contains("UI cache shortened"), "Large terminal output was not shortened.");
            assertTrue(compactOutput.startsWith("H"), "Terminal output head was not retained.");
            assertTrue(compactOutput.endsWith("T".repeat(64 * 1024)), "Terminal output tail was not retained.");

            long cacheModified = Files.getLastModifiedTime(cache).toMillis();
            Thread.sleep(5L);
            appendRaw(raw, userPrompt(701, "new prompt"));
            SessionHistoryUiCachePatchSupport.buildCacheForTest(raw);
            assertTrue(
                Files.getLastModifiedTime(cache).toMillis() >= cacheModified,
                "Appending source history did not invalidate the UI cache."
            );
            List<JsonObject> refreshed = SessionHistoryUiCachePatchSupport.readCachedJsonForTest(raw).stream()
                .map(json -> JsonParser.parseString(json).getAsJsonObject())
                .toList();
            assertContainsText(refreshed, "prompt", "new prompt");
            System.out.println("Session history UI cache tests passed.");
        } finally {
            Files.deleteIfExists(SessionHistoryUiCachePatchSupport.cacheFileForTest(raw));
            Files.deleteIfExists(raw);
            Files.deleteIfExists(directory);
        }
    }

    private static String userPrompt(int id, String prompt) {
        return "{\"type\":\"com.intellij.ml.llm.chat.shared.ChatSessionUserPromptEvent\","
            + "\"id\":{\"id\":" + id + "},\"prompt\":\"" + prompt + "\",\"attachments\":[],"
            + "\"agentId\":{\"id\":\"acp.registry.codex-acp\"}}";
    }

    private static String markdown(int id, String text) {
        return message(id, "{\"kind\":\"com.intellij.ml.llm.aui.events.api.MarkdownBlockUpdatedEvent\","
            + "\"stepId\":\"markdown-" + id + "\",\"textChunk\":\"" + text + "\"}");
    }

    private static String terminal(int id, String output) {
        return message(id, "{\"kind\":\"com.intellij.ml.llm.aui.events.api.TerminalBlockUpdatedEvent\","
            + "\"stepId\":\"terminal-" + id + "\",\"status\":\"COMPLETED\","
            + "\"command\":\"test\",\"output\":\"" + output + "\",\"commandLanguage\":\"SHELL\"}");
    }

    private static String thought(int id, String text) {
        return message(id, "{\"kind\":\"com.intellij.ml.llm.aui.events.api.AgentThoughtBlockUpdatedEvent\","
            + "\"stepId\":\"thought-" + id + "\",\"text\":\"" + text + "\"}");
    }

    private static String result(int id, String result, String changeText) {
        return message(id, "{\"kind\":\"com.intellij.ml.llm.aui.events.api.ResultBlockUpdatedEvent\","
            + "\"result\":\"" + result + "\",\"changes\":[{\"path\":\"test.txt\","
            + "\"before\":\"" + changeText + "\",\"after\":\"" + changeText + "\"}]}");
    }

    private static String message(int id, String event) {
        return "{\"type\":\"com.intellij.ml.llm.chat.shared.ChatSessionMessageBlockEvent\","
            + "\"id\":{\"id\":" + id + "},\"agentId\":{\"id\":\"acp.registry.codex-acp\"},"
            + "\"event\":" + event + "}";
    }

    private static void writeRaw(Path raw, List<String> records) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("AUI_EVENTS_V1");
        for (String record : records) {
            lines.add(Base64.getEncoder().encodeToString(record.getBytes(StandardCharsets.UTF_8)));
        }
        Files.write(raw, lines, StandardCharsets.UTF_8);
    }

    private static void appendRaw(Path raw, String record) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(record.getBytes(StandardCharsets.UTF_8));
        Files.writeString(
            raw,
            encoded + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.APPEND
        );
    }

    private static JsonObject eventById(List<JsonObject> events, int id) {
        return events.stream()
            .filter(event -> event.getAsJsonObject("id").get("id").getAsInt() == id)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Cached event " + id + " was not found."));
    }

    private static void assertContainsText(List<JsonObject> events, String property, String expected) {
        boolean found = events.stream().anyMatch(event -> contains(event, property, expected));
        assertTrue(found, "Cached history does not contain " + property + "=" + expected);
    }

    private static void assertNotContainsText(List<JsonObject> events, String property, String expected) {
        boolean found = events.stream().anyMatch(event -> contains(event, property, expected));
        assertTrue(!found, "Cached history unexpectedly contains " + property + "=" + expected);
    }

    private static boolean contains(JsonObject outer, String property, String expected) {
        JsonObject event = outer.has("event") ? outer.getAsJsonObject("event") : outer;
        return event.has(property)
            && event.get(property).isJsonPrimitive()
            && event.get(property).getAsString().contains(expected);
    }

    private static byte[] sha256(Path file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String message) {
        if (!java.util.Arrays.equals(expected, actual)) {
            throw new AssertionError(message);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
