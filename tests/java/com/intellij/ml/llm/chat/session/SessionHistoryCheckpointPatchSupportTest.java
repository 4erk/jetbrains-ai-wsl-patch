package com.intellij.ml.llm.chat.session;

public final class SessionHistoryCheckpointPatchSupportTest {
    public static void main(String[] args) {
        String countSession = "count-session";
        SessionHistoryCheckpointPatchSupport.resetForTest();
        for (int i = 1; i <= SessionHistoryCheckpointPatchSupport.MAX_PENDING_RECORDS; i++) {
            assertFalse(SessionHistoryCheckpointPatchSupport.shouldFlushBefore(countSession, 10, i));
        }
        assertTrue(SessionHistoryCheckpointPatchSupport.shouldFlushBefore(
            countSession,
            11,
            SessionHistoryCheckpointPatchSupport.MAX_PENDING_RECORDS
        ));
        SessionHistoryCheckpointPatchSupport.startBatch(countSession, 11, 1_000L);
        assertFalse(SessionHistoryCheckpointPatchSupport.shouldFlushBefore(countSession, 11, 2_000L));

        String timeSession = "time-session";
        SessionHistoryCheckpointPatchSupport.resetForTest();
        assertFalse(SessionHistoryCheckpointPatchSupport.shouldFlushBefore(timeSession, 20, 10L));
        assertFalse(SessionHistoryCheckpointPatchSupport.shouldFlushBefore(
            timeSession,
            20,
            10L + SessionHistoryCheckpointPatchSupport.MAX_DIRTY_NANOS - 1L
        ));
        assertFalse(SessionHistoryCheckpointPatchSupport.shouldFlushBefore(
            timeSession,
            20,
            10L + SessionHistoryCheckpointPatchSupport.MAX_DIRTY_NANOS
        ));
        assertTrue(SessionHistoryCheckpointPatchSupport.shouldFlushBefore(
            timeSession,
            21,
            10L + SessionHistoryCheckpointPatchSupport.MAX_DIRTY_NANOS
        ));

        SessionHistoryCheckpointPatchSupport.resetForTest();
        assertFalse(SessionHistoryCheckpointPatchSupport.shouldFlushBefore(timeSession, 30, 100L));
        assertTrue(SessionHistoryCheckpointPatchSupport.shouldFlushAtCheckpoint(
            timeSession,
            100L + SessionHistoryCheckpointPatchSupport.MAX_DIRTY_NANOS
        ));

        SessionHistoryCheckpointPatchSupport.resetForTest();
        assertFalse(SessionHistoryCheckpointPatchSupport.shouldFlushBefore(timeSession, 40, 100L));
        SessionHistoryCheckpointPatchSupport.onFlushForTest(timeSession);
        assertFalse(SessionHistoryCheckpointPatchSupport.shouldFlushBefore(
            timeSession,
            41,
            100L + SessionHistoryCheckpointPatchSupport.MAX_DIRTY_NANOS
        ));
        System.out.println("Session checkpoint policy tests passed.");
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("Expected true");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("Expected false");
        }
    }
}
