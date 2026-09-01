package com.flowengine.domain;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a single execution instance of a workflow.
 * Carries state, variables, retry count, and history of nodes visited.
 */
public class WorkflowExecution implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String executionId;
    private final String workflowId;
    private final int workflowVersion;
    private final String tenantId;
    private final long startTime;
    private long endTime;
    private final AtomicReference<ExecutionState> state;
    private final AtomicReference<String> currentNodeId;
    private final Map<String, Object> variables;
    private final List<NodeExecutionRecord> history;
    private final Map<String, Integer> retryCount;
    private String triggerEventId;
    private String lastError;
    private int priority;
    private final Set<String> completedActions; // idempotency tracking

    public WorkflowExecution(String executionId, String workflowId, int workflowVersion, String tenantId) {
        this.executionId = executionId;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.tenantId = tenantId;
        this.startTime = System.currentTimeMillis();
        this.state = new AtomicReference<>(ExecutionState.CREATED);
        this.currentNodeId = new AtomicReference<>(null);
        this.variables = new HashMap<>();
        this.history = new ArrayList<>();
        this.retryCount = new HashMap<>();
        this.completedActions = new HashSet<>();
        this.priority = 5;
    }

    public String getExecutionId() { return executionId; }
    public String getWorkflowId() { return workflowId; }
    public int getWorkflowVersion() { return workflowVersion; }
    public String getTenantId() { return tenantId; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public void setEndTime(long t) { this.endTime = t; }
    public ExecutionState getState() { return state.get(); }
    public void setState(ExecutionState s) { this.state.set(s); }
    public String getCurrentNodeId() { return currentNodeId.get(); }
    public void setCurrentNodeId(String id) { this.currentNodeId.set(id); }
    public Map<String, Object> getVariables() { return variables; }
    public Object getVariable(String key) { return variables.get(key); }
    public void setVariable(String key, Object value) { variables.put(key, value); }
    public List<NodeExecutionRecord> getHistory() { return history; }
    public Map<String, Integer> getRetryCount() { return retryCount; }
    public int getRetryCount(String nodeId) { return retryCount.getOrDefault(nodeId, 0); }
    public int incrementRetry(String nodeId) {
        int c = retryCount.getOrDefault(nodeId, 0) + 1;
        retryCount.put(nodeId, c);
        return c;
    }
    public String getTriggerEventId() { return triggerEventId; }
    public void setTriggerEventId(String id) { this.triggerEventId = id; }
    public String getLastError() { return lastError; }
    public void setLastError(String e) { this.lastError = e; }
    public int getPriority() { return priority; }
    public void setPriority(int p) { this.priority = p; }
    public Set<String> getCompletedActions() { return completedActions; }
    public boolean isActionCompleted(String actionKey) { return completedActions.contains(actionKey); }
    public void markActionCompleted(String actionKey) { completedActions.add(actionKey); }

    public long getDuration() {
        return (endTime == 0 ? System.currentTimeMillis() : endTime) - startTime;
    }

    public void recordNodeExecution(String nodeId, NodeType type, boolean success, String result) {
        history.add(new NodeExecutionRecord(nodeId, type, System.currentTimeMillis(), success, result));
    }

    @Override
    public String toString() {
        return String.format("Execution[%s, wf=%s, state=%s, current=%s]",
            executionId, workflowId, state.get(), currentNodeId.get());
    }
}
