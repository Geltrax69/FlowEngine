package com.flowengine.domain;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A unit of work submitted to the worker pool.
 */
public class Task implements Serializable, Comparable<Task> {
    private static final long serialVersionUID = 1L;
    private static final AtomicInteger taskCounter = new AtomicInteger(0);

    private final int taskSeq;
    private final String executionId;
    private final String nodeId;
    private final TaskType taskType;
    private final long createdAt;
    private final int priority;
    private final Event triggerEvent;
    private volatile long startTime;
    private volatile long endTime;
    private volatile String workerId;

    public enum TaskType {
        EXECUTE_NODE,
        SCHEDULE_DELAY,
        APPROVAL_TIMEOUT,
        HEARTBEAT,
        METRICS_COLLECT
    }

    public Task(String executionId, String nodeId, TaskType type, int priority, Event triggerEvent) {
        this.taskSeq = taskCounter.incrementAndGet();
        this.executionId = executionId;
        this.nodeId = nodeId;
        this.taskType = type;
        this.createdAt = System.currentTimeMillis();
        this.priority = priority;
        this.triggerEvent = triggerEvent;
    }

    public int getTaskSeq() { return taskSeq; }
    public String getExecutionId() { return executionId; }
    public String getNodeId() { return nodeId; }
    public TaskType getTaskType() { return taskType; }
    public long getCreatedAt() { return createdAt; }
    public int getPriority() { return priority; }
    public Event getTriggerEvent() { return triggerEvent; }
    public long getStartTime() { return startTime; }
    public void setStartTime(long t) { this.startTime = t; }
    public long getEndTime() { return endTime; }
    public void setEndTime(long t) { this.endTime = t; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String id) { this.workerId = id; }
    public long getDuration() { return endTime > 0 ? endTime - startTime : 0; }

    @Override
    public int compareTo(Task o) {
        int cmp = Integer.compare(this.priority, o.priority);
        return cmp != 0 ? -cmp : Long.compare(this.createdAt, o.createdAt);
    }

    @Override
    public String toString() {
        return String.format("Task[#%d exec=%s node=%s type=%s priority=%d]",
            taskSeq, executionId, nodeId, taskType, priority);
    }
}
