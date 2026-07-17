package com.intellij.ml.llm.core.chat.ui.chat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CodexUsageLimitPatchSupportTest {
    private static final String SNAPSHOT = """
        {
          "schema": 1,
          "updatedAt": 1783944000000,
          "source": "account/rateLimits/read",
          "rateLimits": {
            "limitId": "codex",
            "limitName": null,
            "primary": {"usedPercent": 8, "windowDurationMins": 10080, "resetsAt": 1784542511},
            "secondary": null,
            "rateLimitReachedType": null
          },
          "rateLimitsByLimitId": {
            "codex": {
              "limitId": "codex",
              "limitName": null,
              "_jetbrainsUpdatedAt": 1783944000000,
              "primary": {"usedPercent": 8, "windowDurationMins": 10080, "resetsAt": 1784542511},
              "secondary": null,
              "rateLimitReachedType": null
            },
            "codex_bengalfox": {
              "limitId": "codex_bengalfox",
              "limitName": "GPT-5.3-Codex-Spark",
              "_jetbrainsUpdatedAt": 1783943000000,
              "primary": {"usedPercent": 0, "windowDurationMins": 10080, "resetsAt": 1784549245},
              "secondary": null,
              "rateLimitReachedType": null
            }
          }
        }
        """;

    private static final String TWO_WINDOWS = """
        {
          "schema": 1,
          "updatedAt": 1783944000000,
          "rateLimitsByLimitId": {
            "codex": {
              "limitId": "codex",
              "primary": {"usedPercent": 17.4, "windowDurationMins": 300, "resetsAt": 1783945000},
              "secondary": {"usedPercent": 23.2, "windowDurationMins": 10080, "resetsAt": 1784542511}
            }
          }
        }
        """;

    public static void main(String[] args) throws Exception {
        assertEquals("codex|10080=92", CodexUsageLimitPatchSupport.summarizeForTest(SNAPSHOT, "GPT-5.6-Luna (high)"));
        assertEquals("codex_bengalfox|10080=100", CodexUsageLimitPatchSupport.summarizeForTest(SNAPSHOT, "gpt-5.3-codex-spark[xhigh]"));
        assertEquals("codex|300=83,10080=77", CodexUsageLimitPatchSupport.summarizeForTest(TWO_WINDOWS, "gpt-5.5[high]"));

        Path codexHome = Files.createTempDirectory("codex-rate-limit-test");
        try {
            Files.writeString(codexHome.resolve("jetbrains-rate-limits.json"), "{invalid", StandardCharsets.UTF_8);
            Files.writeString(
                codexHome.resolve("jetbrains-rate-limits.json.last-good"),
                SNAPSHOT,
                StandardCharsets.UTF_8
            );
            assertEquals(
                "1783944000000|codex|10080=92",
                CodexUsageLimitPatchSupport.summarizeBestForTest(codexHome, "gpt-5.6")
            );
            assertEquals(
                "1783943000000|codex_bengalfox|10080=100",
                CodexUsageLimitPatchSupport.summarizeBestForTest(codexHome, "gpt-5.3-codex-spark")
            );

            Files.writeString(
                codexHome.resolve("jetbrains-rate-limits.json"),
                TWO_WINDOWS.replace("1783944000000", "1783945000000"),
                StandardCharsets.UTF_8
            );
            assertEquals(
                "1783945000000|codex|300=83,10080=77",
                CodexUsageLimitPatchSupport.summarizeBestForTest(codexHome, "gpt-5.5")
            );
        } finally {
            Files.deleteIfExists(codexHome.resolve("jetbrains-rate-limits.json"));
            Files.deleteIfExists(codexHome.resolve("jetbrains-rate-limits.json.last-good"));
            Files.deleteIfExists(codexHome);
        }
        System.out.println("Usage-limit parser tests passed.");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
