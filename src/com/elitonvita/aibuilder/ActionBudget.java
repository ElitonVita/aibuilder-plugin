package com.elitonvita.aibuilder;

import java.util.ArrayDeque;
import java.util.Deque;

/** Sliding minute budget. Check attempts freely; record only successful world changes. */
public final class ActionBudget {
    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED = 100_000;
    private final Deque<Long> recorded = new ArrayDeque<>();
    private long lastObserved = Long.MIN_VALUE;
    private Long saturatedAt;

    /** A clock rollback denies actions until time catches up, without removing records. */
    public boolean allows(long now, int maxPerMinute) {
        if (now < lastObserved) return false;
        lastObserved = now;
        while (!recorded.isEmpty() && expired(now, recorded.peekFirst())) recorded.removeFirst();
        if (saturatedAt != null) {
            if (!expired(now, saturatedAt)) return false;
            saturatedAt = null;
        }
        return maxPerMinute > 0 && recorded.size() < Math.min(maxPerMinute, MAX_TRACKED);
    }

    /** Conservatively timestamps a change if the clock moves backward after the check. */
    public void record(long now) {
        long timestamp = Math.max(now, lastObserved);
        lastObserved = timestamp;
        while (!recorded.isEmpty() && expired(timestamp, recorded.peekFirst())) recorded.removeFirst();
        if (recorded.size() == MAX_TRACKED) {
            // Preserve bounded memory without dropping changes from an oversubscribed window.
            saturatedAt = timestamp;
            return;
        }
        recorded.addLast(timestamp);
    }

    private static boolean expired(long now, long timestamp) {
        // A negative difference from overflow is conservatively treated as unexpired.
        return now >= timestamp && now - timestamp >= WINDOW_MS;
    }
}
