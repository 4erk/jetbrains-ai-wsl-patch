package com.intellij.ml.llm.chat.session;

import com.intellij.ml.llm.chat.shared.ChatSessionMessageEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionHistoryCheckpointPatchSupport {
    static final long MAX_DIRTY_NANOS = 30_000_000_000L;
    static final int MAX_PENDING_RECORDS = 256;

    private static final Map<String, DirtyState> DIRTY_STATES =
        new ConcurrentHashMap<>();

    private SessionHistoryCheckpointPatchSupport() {}

    public static void beforeRecord(
        SessionHistoryStorage storage,
        SessionHistoryStorage.PersistanceId sessionId,
        ChatSessionMessageEvent event
    ) {
        if (storage == null || sessionId == null || event == null) {
            return;
        }
        long nowNanos = System.nanoTime();
        String id = sessionId.getId();
        int eventId = event.getId().getId();
        if (shouldFlushBefore(id, eventId, nowNanos)) {
            storage.flush(sessionId);
            startBatch(id, eventId, nowNanos);
        }
    }

    public static void afterCheckpoint(
        SessionHistoryStorage storage,
        SessionHistoryStorage.PersistanceId sessionId
    ) {
        if (storage != null && sessionId != null && shouldFlushAtCheckpoint(sessionId.getId(), System.nanoTime())) {
            storage.flush(sessionId);
        }
    }

    public static void onFlush(SessionHistoryStorage.PersistanceId sessionId) {
        if (sessionId != null) {
            onFlushForTest(sessionId.getId());
        }
    }

    static boolean shouldFlushBefore(String sessionId, int eventId, long nowNanos) {
        DirtyState state = DIRTY_STATES.computeIfAbsent(sessionId, ignored -> new DirtyState(nowNanos, eventId));
        synchronized (state) {
            state.recordCount++;
            boolean due = state.recordCount >= MAX_PENDING_RECORDS
                || nowNanos - state.startedAtNanos >= MAX_DIRTY_NANOS;
            if (due && state.lastEventId != eventId) {
                DIRTY_STATES.remove(sessionId, state);
                return true;
            }
            state.lastEventId = eventId;
            return false;
        }
    }

    static boolean shouldFlushAtCheckpoint(String sessionId, long nowNanos) {
        DirtyState state = DIRTY_STATES.computeIfAbsent(sessionId, ignored -> new DirtyState(nowNanos, -1));
        synchronized (state) {
            state.recordCount++;
            boolean due = state.recordCount >= MAX_PENDING_RECORDS
                || nowNanos - state.startedAtNanos >= MAX_DIRTY_NANOS;
            if (due) {
                DIRTY_STATES.remove(sessionId, state);
            }
            return due;
        }
    }

    static void resetForTest() {
        DIRTY_STATES.clear();
    }

    static void onFlushForTest(String sessionId) {
        DIRTY_STATES.remove(sessionId);
    }

    static void startBatch(String sessionId, int eventId, long nowNanos) {
        DirtyState state = new DirtyState(nowNanos, eventId);
        state.recordCount = 1;
        DIRTY_STATES.put(sessionId, state);
    }

    private static final class DirtyState {
        private final long startedAtNanos;
        private int lastEventId;
        private int recordCount;

        private DirtyState(long startedAtNanos, int lastEventId) {
            this.startedAtNanos = startedAtNanos;
            this.lastEventId = lastEventId;
        }
    }
}
