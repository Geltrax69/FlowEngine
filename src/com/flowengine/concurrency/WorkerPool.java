package com.flowengine.concurrency;

import com.flowengine.domain.*;
import com.flowengine.engine.WorkflowEngine;
import com.flowengine.engine.FailureInjector;
import com.flowengine.metrics.MetricsManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Manages a pool of worker threads that execute workflow tasks concurrently.
 * Supports dynamic scaling, pausing, and failure simulation.
 */
public class WorkerPool {

    private final ExecutorService executor;
    private final MetricsManager metrics;
    private final FailureInjector failureInjector;
    private final WorkflowEngine engine;

    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private final AtomicInteger totalWorkers = new AtomicInteger(0);
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    private final AtomicInteger failedTasks = new AtomicInteger(0);
    private final Map<String, WorkerStatus> workerStatuses = new ConcurrentHashMap<>();
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final BlockingQueue<Task> taskQueue;
    private final ScheduledExecutorService healthMonitor;
    private volatile int maxWorkers;
    private final AtomicInteger idleWorkers = new AtomicInteger(0);

    public static class WorkerStatus {
        public String workerId;
        public volatile boolean busy;
        public volatile String currentTask;
        public volatile long lastTaskTime;
        public volatile long tasksCompleted;
        public volatile long tasksFailed;
        public volatile WorkerState state;

        public enum WorkerState { IDLE, RUNNING, PAUSED, DEAD }
    }

    public WorkerPool(int numWorkers, WorkflowEngine engine, MetricsManager metrics, FailureInjector failureInjector) {
        this.maxWorkers = numWorkers;
        this.engine = engine;
        this.metrics = metrics;
        this.failureInjector = failureInjector;
        this.taskQueue = new PriorityBlockingQueue<>(5000);
        this.executor = Executors.newFixedThreadPool(numWorkers, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        this.healthMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "worker-health-monitor");
            t.setDaemon(true);
            return t;
        });
        startWorkers(numWorkers);
        startHealthMonitor();
    }

    private void startWorkers(int count) {
        for (int i = 0; i < count; i++) {
            String workerId = "worker-" + totalWorkers.incrementAndGet();
            WorkerStatus status = new WorkerStatus();
            status.workerId = workerId;
            status.state = WorkerStatus.WorkerState.IDLE;
            workerStatuses.put(workerId, status);
            executor.submit(() -> runWorker(workerId));
        }
    }

    private void runWorker(String workerId) {
        WorkerStatus status = workerStatuses.get(workerId);
        while (!Thread.currentThread().isInterrupted()) {
            if (paused.get()) {
                status.state = WorkerStatus.WorkerState.PAUSED;
                try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                continue;
            }
            status.state = WorkerStatus.WorkerState.IDLE;
            idleWorkers.incrementAndGet();
            try {
                Task task = taskQueue.poll(500, TimeUnit.MILLISECONDS);
                if (task == null) continue;
                idleWorkers.decrementAndGet();
                status.state = WorkerStatus.WorkerState.RUNNING;
                activeWorkers.incrementAndGet();
                status.currentTask = task.toString();
                status.lastTaskTime = System.currentTimeMillis();
                task.setWorkerId(workerId);
                task.setStartTime(System.currentTimeMillis());

                // Simulate worker crash if kill worker is enabled
                if (failureInjector.isKillWorkerEnabled() && ThreadLocalRandom.current().nextDouble() < 0.1) {
                    failureInjector.recordKilledWorker();
                    status.state = WorkerStatus.WorkerState.DEAD;
                    failedTasks.incrementAndGet();
                    metrics.recordWorkerFailure();
                    // Respawn
                    respawnWorker(workerId);
                    break;
                }

                // Slow network simulation
                int delay = failureInjector.getNetworkDelay();
                if (delay > 0) Thread.sleep(delay);

                try {
                    engine.executeTask(task);
                    completedTasks.incrementAndGet();
                    status.tasksCompleted++;
                } catch (Exception e) {
                    failedTasks.incrementAndGet();
                    status.tasksFailed++;
                    metrics.recordWorkerFailure();
                } finally {
                    task.setEndTime(System.currentTimeMillis());
                    activeWorkers.decrementAndGet();
                    status.currentTask = null;
                    metrics.recordTaskCompleted(task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void respawnWorker(String oldWorkerId) {
        String newId = "worker-" + totalWorkers.incrementAndGet();
        WorkerStatus status = new WorkerStatus();
        status.workerId = newId;
        status.state = WorkerStatus.WorkerState.IDLE;
        workerStatuses.put(newId, status);
        executor.submit(() -> runWorker(newId));
    }

    private void startHealthMonitor() {
        healthMonitor.scheduleAtFixedRate(() -> {
            metrics.updateWorkerStats(
                activeWorkers.get(),
                idleWorkers.get(),
                workerStatuses.size(),
                completedTasks.get(),
                failedTasks.get(),
                taskQueue.size()
            );
        }, 1, 1, TimeUnit.SECONDS);
    }

    public void submit(Task task) {
        taskQueue.offer(task);
    }

    public boolean trySubmit(Task task) {
        return taskQueue.offer(task);
    }

    public void submitAll(Collection<Task> tasks) {
        for (Task t : tasks) submit(t);
    }

    public void pause() { paused.set(true); }
    public void resume() { paused.set(false); }
    public boolean isPaused() { return paused.get(); }

    public void setMaxWorkers(int n) {
        int diff = n - workerStatuses.size();
        if (diff > 0) startWorkers(diff);
        this.maxWorkers = n;
    }

    public int getActiveWorkers() { return activeWorkers.get(); }
    public int getIdleWorkers() { return idleWorkers.get(); }
    public int getTotalWorkers() { return workerStatuses.size(); }
    public int getCompletedTasks() { return completedTasks.get(); }
    public int getFailedTasks() { return failedTasks.get(); }
    public int getQueueSize() { return taskQueue.size(); }
    public Collection<WorkerStatus> getWorkerStatuses() { return workerStatuses.values(); }

    public void shutdown() {
        healthMonitor.shutdown();
        executor.shutdownNow();
    }
}
