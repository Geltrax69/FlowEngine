package com.flowengine.engine;

import java.util.*;
import java.util.concurrent.*;

/**
 * Ensures that events and actions are processed at most once.
 * Uses a sliding-window in-memory cache with TTL.
 */
public class IdempotencyManager {
    private final ConcurrentHashMap<String, Entry> seen = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "idempotency-cleanup");
        t.setDaemon(true);
        return t;
    });
    private volatile long ttlMs = 300_000; // 5 minutes

    private static class Entry {
        final long timestamp;
        final Object result;
        Entry(long timestamp, Object result) {
            this.timestamp = timestamp;
            this.result = result;
        }
    }

    public IdempotencyManager() {
        // Clean up expired entries every 60 seconds
        scheduler.scheduleAtFixedRate(this::evictExpired, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Check if the given idempotency key was already processed.
     * @return the cached result if seen, null if not seen (first time)
     */
    public Object checkAndMark(String key) {
        if (key == null || key.isBlank()) return null;
        long now = System.currentTimeMillis();
        Entry existing = seen.putIfAbsent(key, new Entry(now, SENTINEL));
        if (existing != null) {
            return existing.result == SENTINEL ? null : existing.result;
        }
        return null; // first time
    }

    /**
     * Check if the given idempotency key was already processed (read-only).
     */
    public boolean isProcessed(String key) {
        if (key == null || key.isBlank()) return false;
        return seen.containsKey(key);
    }

    /**
     * Store the result for an idempotency key.
     */
    public void markCompleted(String key, Object result) {
        if (key == null || key.isBlank()) return;
        seen.put(key, new Entry(System.currentTimeMillis(), result != null ? result : SENTINEL));
    }

    /**
     * Remove an idempotency key (e.g. when workflow is cancelled).
     */
    public void remove(String key) {
        if (key != null) seen.remove(key);
    }

    /**
     * Build an idempotency key from execution + node + event info.
     */
    public static String buildKey(String executionId, String nodeId, String eventId) {
        return executionId + ":" + nodeId + ":" + eventId;
    }

    public int size() { return seen.size(); }

    private void evictExpired() {
        long cutoff = System.currentTimeMillis() - ttlMs;
        seen.entrySet().removeIf(e -> e.getValue().timestamp < cutoff);
    }

    public void setTtlMs(long ms) { this.ttlMs = ms; }

    public void shutdown() {
        scheduler.shutdown();
    }

    private static final Object SENTINEL = new Object();
}
