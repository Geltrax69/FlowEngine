package com.flowengine.metrics;

import com.flowengine.domain.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Central metrics collection and aggregation.
 * Tracks workflow executions, events, actions, workers, and system health.
 */
public class MetricsManager {

    private final AtomicLong totalEventsPublished = new AtomicLong(0);
    private final AtomicLong totalEventsConsumed = new AtomicLong(0);
    private final AtomicLong totalEventsDropped = new AtomicLong(0);
    private final AtomicLong totalDuplicates = new AtomicLong(0);
    private final AtomicLong rateLimitedEvents = new AtomicLong(0);

    private final AtomicInteger activeWorkflows = new AtomicInteger(0);
    private final AtomicInteger completedWorkflows = new AtomicInteger(0);
    private final AtomicInteger failedWorkflows = new AtomicInteger(0);
    private final AtomicInteger queuedTasks = new AtomicInteger(0);
    private final AtomicInteger retryingTasks = new AtomicInteger(0);
    private final AtomicInteger waitingTasks = new AtomicInteger(0);

    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private final AtomicInteger idleWorkers = new AtomicInteger(0);
    private final AtomicInteger totalWorkers = new AtomicInteger(0);
    private final AtomicInteger workerFailures = new AtomicInteger(0);
    private final AtomicInteger completedTasks = new AtomicInteger(0);

    private final AtomicLong totalExecutionTime = new AtomicLong(0);
    private final AtomicInteger executionCount = new AtomicInteger(0);

    // Time-series data for graphs (last 60 seconds)
    private static final int RING_SIZE = 60;
    private final long[] timestamps = new long[RING_SIZE];
    private final double[] eventsPerSec = new double[RING_SIZE];
    private final double[] execTimes = new double[RING_SIZE];
    private final int[] activeWorkflowCounts = new int[RING_SIZE];
    private final int[] queueSizes = new int[RING_SIZE];
    private final AtomicInteger ringIndex = new AtomicInteger(0);
    private final ReadWriteLock ringLock = new ReentrantReadWriteLock();

    // Action stats
    private final Map<ActionType, AtomicInteger> actionSuccesses = new ConcurrentHashMap<>();
    private final Map<ActionType, AtomicInteger> actionFailures = new ConcurrentHashMap<>();
    private final Map<EventType, AtomicInteger> eventsByType = new ConcurrentHashMap<>();

    // Failure reasons
    private final List<String> recentFailureReasons = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, AtomicInteger> failureReasonCounts = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "metrics-collector");
        t.setDaemon(true);
        return t;
    });

    public MetricsManager() {
        // Record timestamp every second
        scheduler.scheduleAtFixedRate(this::collectSnapshot, 1, 1, TimeUnit.SECONDS);
        for (ActionType at : ActionType.values()) {
            actionSuccesses.put(at, new AtomicInteger(0));
            actionFailures.put(at, new AtomicInteger(0));
        }
        for (EventType et : EventType.values()) {
            eventsByType.put(et, new AtomicInteger(0));
        }
    }

    private void collectSnapshot() {
        int idx = ringIndex.updateAndGet(i -> (i + 1) % RING_SIZE);
        timestamps[idx] = System.currentTimeMillis();
        eventsPerSec[idx] = 0;
        execTimes[idx] = 0;
        activeWorkflowCounts[idx] = activeWorkflows.get();
        queueSizes[idx] = queuedTasks.get();
    }

    public void recordWorkflowStarted() { activeWorkflows.incrementAndGet(); }
    public void recordWorkflowCompleted(long durationMs) {
        activeWorkflows.decrementAndGet();
        completedWorkflows.incrementAndGet();
        totalExecutionTime.addAndGet(durationMs);
        executionCount.incrementAndGet();
    }
    public void recordWorkflowFailed(long durationMs) {
        activeWorkflows.decrementAndGet();
        failedWorkflows.incrementAndGet();
        totalExecutionTime.addAndGet(durationMs);
        executionCount.incrementAndGet();
    }
    public void recordWorkflowCancelled() { activeWorkflows.decrementAndGet(); }

    public void recordEventProcessed(EventType type) {
        totalEventsConsumed.incrementAndGet();
        eventsByType.computeIfAbsent(type, k -> new AtomicInteger(0)).incrementAndGet();
    }
    public void recordEventDropped() { totalEventsDropped.incrementAndGet(); }
    public void recordEventRateLimited() { rateLimitedEvents.incrementAndGet(); }
    public void recordEventPublished() { totalEventsPublished.incrementAndGet(); }
    public void recordEventBusDuplicates() { totalDuplicates.incrementAndGet(); }

    public void recordActionSuccess(ActionType type, long durationMs) {
        AtomicInteger counter = actionSuccesses.get(type);
        if (counter != null) counter.incrementAndGet();
    }
    public void recordActionFailure(ActionType type) {
        AtomicInteger counter = actionFailures.get(type);
        if (counter != null) counter.incrementAndGet();
    }

    public void recordWorkerFailure() { workerFailures.incrementAndGet(); }
    public void recordTaskCompleted(Task task) { completedTasks.incrementAndGet(); }
    public void updateWorkerStats(int active, int idle, int total, int completed, int failed, int queueSize) {
        activeWorkers.set(active);
        idleWorkers.set(idle);
        totalWorkers.set(total);
        completedTasks.set(completed);
        workerFailures.set(failed);
        queuedTasks.set(queueSize);
    }
    public void updateEventBusStats(long published, long consumed, int queueSize, long duplicates) {
        totalEventsPublished.set(published);
        totalEventsConsumed.set(consumed);
        queuedTasks.set(queueSize);
        totalDuplicates.set(duplicates);
    }

    public void recordFailureReason(String reason) {
        recentFailureReasons.add(reason + " [" + System.currentTimeMillis() + "]");
        if (recentFailureReasons.size() > 100) recentFailureReasons.remove(0);
        failureReasonCounts.computeIfAbsent(reason, k -> new AtomicInteger(0)).incrementAndGet();
    }

    // --- Read API for Dashboard ---
    public int getActiveWorkflows() { return activeWorkflows.get(); }
    public int getCompletedWorkflows() { return completedWorkflows.get(); }
    public int getFailedWorkflows() { return failedWorkflows.get(); }
    public int getActiveWorkers() { return activeWorkers.get(); }
    public int getIdleWorkers() { return idleWorkers.get(); }
    public int getTotalWorkers() { return totalWorkers.get(); }
    public int getCompletedTasks() { return completedTasks.get(); }
    public int getWorkerFailures() { return workerFailures.get(); }
    public long getTotalEventsPublished() { return totalEventsPublished.get(); }
    public long getTotalEventsConsumed() { return totalEventsConsumed.get(); }
    public long getTotalDuplicates() { return totalDuplicates.get(); }
    public double getAvgExecutionTime() {
        int cnt = executionCount.get();
        return cnt == 0 ? 0 : (double) totalExecutionTime.get() / cnt;
    }
    public double getFailureRate() {
        int total = completedWorkflows.get() + failedWorkflows.get();
        return total == 0 ? 0 : (double) failedWorkflows.get() / total * 100;
    }
    public double getEventsPerSecond() {
        long consumed = totalEventsConsumed.get();
        // Estimate based on last second
        return consumed / Math.max(1, (System.currentTimeMillis() - getStartTime()) / 1000.0);
    }
    public long getStartTime() { return startTime; }
    public Map<ActionType, AtomicInteger> getActionSuccesses() { return actionSuccesses; }
    public Map<ActionType, AtomicInteger> getActionFailures() { return actionFailures; }
    public Map<EventType, AtomicInteger> getEventsByType() { return eventsByType; }
    public List<String> getRecentFailureReasons() { return new ArrayList<>(recentFailureReasons); }
    public Map<String, AtomicInteger> getFailureReasonCounts() { return failureReasonCounts; }
    public int getQueuedTasks() { return queuedTasks.get(); }
    public int getRetryingTasks() { return retryingTasks.get(); }
    public void incrementRetrying() { retryingTasks.incrementAndGet(); }
    public void decrementRetrying() { retryingTasks.decrementAndGet(); }
    public void incrementWaiting() { waitingTasks.incrementAndGet(); }
    public void decrementWaiting() { waitingTasks.decrementAndGet(); }
    public int getWaitingTasks() { return waitingTasks.get(); }

    // Ring buffer access for graphs
    public double[] getEventsPerSecSeries() { return eventsPerSec.clone(); }
    public int[] getActiveWorkflowSeries() { return activeWorkflowCounts.clone(); }
    public int[] getQueueSizeSeries() { return queueSizes.clone(); }
    public long[] getTimestamps() { return timestamps.clone(); }

    public void shutdown() { scheduler.shutdown(); }

    private static long startTime = System.currentTimeMillis();
}
