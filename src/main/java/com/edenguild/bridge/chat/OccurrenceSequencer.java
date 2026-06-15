package com.edenguild.bridge.chat;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Assigns a deterministic 1-based occurrence index to repeated identical events
 * within a short rolling window. Used for guild-bank events so depositing the same
 * item several times in a row stays distinct (3 deposits = seq 1..3) instead of
 * being collapsed by content-based dedup.
 *
 * <p>Every connected mod sees the same chat in the same order, so within the window
 * they compute the same seq for a given occurrence — and the backend's cross-client
 * dedup collapses the same physical event seen by multiple clients. The window is
 * deliberately short so that <em>stale</em> earlier deposits (which different clients
 * may or may not have witnessed, depending on when they connected) don't inflate the
 * count differently across clients and cause duplicate Discord messages.
 *
 * <p>A tiny re-emit guard also coalesces the exact same line delivered twice in
 * quick succession (a single physical event re-sent in the same tick) onto one seq.
 */
public final class OccurrenceSequencer {
    // The same line re-delivered within this gap is a re-emit, not a new deposit.
    private static final long REEMIT_GUARD_MS = 200L;

    private final long windowMillis;
    private final Map<String, ArrayDeque<Long>> seen = new HashMap<>();

    public OccurrenceSequencer(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /** Record an occurrence of {@code signature} and return its index in the window. */
    public synchronized int next(String signature) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> times = seen.computeIfAbsent(signature, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
            times.pollFirst();
        }
        // A near-instant repeat is the same line emitted twice; reuse its index so the
        // backend dedups it instead of treating it as a second deposit.
        if (!times.isEmpty() && now - times.peekLast() < REEMIT_GUARD_MS) {
            return times.size();
        }
        times.addLast(now);
        return times.size();
    }
}
