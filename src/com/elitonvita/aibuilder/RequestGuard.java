package com.elitonvita.aibuilder;

/** Main-thread-owned lifecycle for a single asynchronous request and its completion. */
public final class RequestGuard {
    private long epoch;
    private boolean thinking;

    /** Starts one request. Finish or invalidate it before starting another. */
    public long begin() {
        if (thinking) throw new IllegalStateException("ja existe um pedido em andamento");
        thinking = true;
        return ++epoch;
    }

    /** Only the current, still-running request may publish a response. */
    public boolean accepts(long token) {
        return thinking && token == epoch;
    }

    public boolean isThinking() {
        return thinking;
    }

    /** Releases the current request on success or failure; stale completions do nothing. */
    public boolean complete(long token) {
        if (!accepts(token)) return false;
        thinking = false;
        return true;
    }

    /** Stops a request and prevents its later response from becoming a new plan. */
    public void invalidate() {
        ++epoch;
        thinking = false;
    }
}
