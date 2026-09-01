package com.flowengine.engine;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Injects various failures into the system for testing resilience.
 * All injection methods are togglable via public API.
 */
public class FailureInjector {

    private volatile boolean killWorkerEnabled = false;
    private volatile boolean floodEventsEnabled = false;
    private volatile boolean duplicateEventsEnabled = false;
    private volatile boolean slowNetworkEnabled = false;
    private volatile boolean randomFailureEnabled = false;
    private volatile boolean corruptEventEnabled = false;
    private volatile boolean pauseQueueEnabled = false;
    private volatile double randomFailureRate = 0.05;
    private volatile int slowNetworkDelayMs = 5000;

    private final AtomicInteger killedWorkers = new AtomicInteger(0);
    private final AtomicInteger injectedFailures = new AtomicInteger(0);
    private final AtomicLong eventsFlooded = new AtomicLong(0);
    private final AtomicLong duplicatesInjected = new AtomicLong(0);
    private final List<String> recentFailures = Collections.synchronizedList(new ArrayList<>());

    // Failure reasons pool
    private static final String[] FAILURE_REASONS = {
        "Connection timeout",
        "Service unavailable (503)",
        "Circuit breaker open",
        "Rate limit exceeded",
        "Resource exhausted",
        "Null pointer in handler",
        "Database connection lost",
        "Message queue full",
        "Worker terminated unexpectedly",
        "Invalid state transition"
    };

    public boolean shouldInjectFailure(String actionKey) {
        if (randomFailureEnabled && ThreadLocalRandom.current().nextDouble() < randomFailureRate) {
            recordFailure("Random failure injected for: " + actionKey);
            return true;
        }
        return false;
    }

    public String getLastFailureReason() {
        List<String> snapshot = new ArrayList<>(recentFailures);
        if (snapshot.isEmpty()) return "Unknown failure";
        return snapshot.get(snapshot.size() - 1);
    }

    public void recordFailure(String reason) {
        recentFailures.add(reason + " [" + System.currentTimeMillis() + "]");
        injectedFailures.incrementAndGet();
        if (recentFailures.size() > 100) recentFailures.remove(0);
    }

    // --- Toggle methods ---
    public void setKillWorker(boolean enabled) { this.killWorkerEnabled = enabled; }
    public void setFloodEvents(boolean enabled) { this.floodEventsEnabled = enabled; }
    public void setDuplicateEvents(boolean enabled) { this.duplicateEventsEnabled = enabled; }
    public void setSlowNetwork(boolean enabled) { this.slowNetworkEnabled = enabled; }
    public void setRandomFailure(boolean enabled) { this.randomFailureEnabled = enabled; }
    public void setCorruptEvent(boolean enabled) { this.corruptEventEnabled = enabled; }
    public void setPauseQueue(boolean enabled) { this.pauseQueueEnabled = enabled; }
    public void setRandomFailureRate(double rate) { this.randomFailureRate = Math.max(0, Math.min(1, rate)); }
    public void setSlowNetworkDelay(int ms) { this.slowNetworkDelayMs = ms; }

    // --- Query methods ---
    public boolean isKillWorkerEnabled() { return killWorkerEnabled; }
    public boolean isFloodEventsEnabled() { return floodEventsEnabled; }
    public boolean isDuplicateEventsEnabled() { return duplicateEventsEnabled; }
    public boolean isSlowNetworkEnabled() { return slowNetworkEnabled; }
    public boolean isRandomFailureEnabled() { return randomFailureEnabled; }
    public boolean isCorruptEventEnabled() { return corruptEventEnabled; }
    public boolean isPauseQueueEnabled() { return pauseQueueEnabled; }
    public int getSlowNetworkDelayMs() { return slowNetworkDelayMs; }
    public int getKilledWorkers() { return killedWorkers.get(); }
    public int getInjectedFailures() { return injectedFailures.get(); }
    public long getEventsFlooded() { return eventsFlooded.get(); }
    public long getDuplicatesInjected() { return duplicatesInjected.get(); }
    public List<String> getRecentFailures() { return new ArrayList<>(recentFailures); }

    public void recordKilledWorker() { killedWorkers.incrementAndGet(); }
    public void recordFloodedEvents(int count) { eventsFlooded.addAndGet(count); }
    public void recordDuplicate() { duplicatesInjected.incrementAndGet(); }

    public boolean shouldInjectCorruptEvent() { return corruptEventEnabled && ThreadLocalRandom.current().nextDouble() < 0.3; }

    public String randomFailureReason() {
        return FAILURE_REASONS[ThreadLocalRandom.current().nextInt(FAILURE_REASONS.length)];
    }

    /** Returns a random delay if slow network is enabled, else 0 */
    public int getNetworkDelay() {
        return slowNetworkEnabled ? ThreadLocalRandom.current().nextInt(100, slowNetworkDelayMs + 1) : 0;
    }

    /** Reset all failure injection states */
    public void resetAll() {
        killWorkerEnabled = false;
        floodEventsEnabled = false;
        duplicateEventsEnabled = false;
        slowNetworkEnabled = false;
        randomFailureEnabled = false;
        corruptEventEnabled = false;
        pauseQueueEnabled = false;
    }
}
