package com.flowengine.engine;

import com.flowengine.domain.*;
import com.flowengine.event.EventBus;
import com.flowengine.expression.ExpressionEvaluator;
import com.flowengine.metrics.MetricsManager;
import com.flowengine.concurrency.WorkerPool;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Core workflow execution engine.
 * Reads the workflow graph, executes nodes, handles branching, retries, and delays.
 */
public class WorkflowEngine {

    private final Map<String, Workflow> workflows = new ConcurrentHashMap<>();
    private final Map<String, WorkflowExecution> executions = new ConcurrentHashMap<>();
    private final EventBus eventBus;
    private final WorkerPool workerPool;
    private final ActionExecutor actionExecutor;
    private final IdempotencyManager idempotencyManager;
    private final MetricsManager metrics;
    private final FailureInjector failureInjector;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "workflow-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong executionCounter = new AtomicLong(0);
    private final java.util.concurrent.locks.ReadWriteLock executionsLock = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private volatile boolean running = true;

    public WorkflowEngine(EventBus eventBus, MetricsManager metrics, FailureInjector failureInjector, int numWorkers) {
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.failureInjector = failureInjector;
        this.idempotencyManager = new IdempotencyManager();
        this.actionExecutor = new ActionExecutor(metrics, failureInjector);
        this.workerPool = new WorkerPool(numWorkers, this, metrics, failureInjector);

        // Subscribe to all event types
        for (EventType et : EventType.values()) {
            eventBus.subscribe(et, this::onWorkflowEvent);
        }
    }

    // --- Workflow Management ---
    public void registerWorkflow(Workflow workflow) {
        workflows.put(workflow.getId(), workflow);
    }

    public void unregisterWorkflow(String workflowId) {
        workflows.remove(workflowId);
    }

    public Workflow getWorkflow(String workflowId) {
        return workflows.get(workflowId);
    }

    public Collection<Workflow> getAllWorkflows() { return workflows.values(); }

    // --- Execution Management ---
    public WorkflowExecution startWorkflow(String workflowId, Event triggerEvent) {
        Workflow workflow = workflows.get(workflowId);
        if (workflow == null) throw new RuntimeException("Workflow not found: " + workflowId);

        String execId = workflowId + "-" + executionCounter.incrementAndGet();
        WorkflowExecution execution = new WorkflowExecution(execId, workflowId, workflow.getVersion(), workflow.getTenantId());

        if (triggerEvent != null) {
            execution.setTriggerEventId(triggerEvent.getId());
            execution.getVariables().putAll(triggerEvent.getPayload());
            execution.setVariable("_eventType", triggerEvent.getType().name());
        }

        execution.getVariables().putAll(workflow.getVariables());
        execution.setState(ExecutionState.QUEUED);

        executionsLock.writeLock().lock();
        try {
            executions.put(execId, execution);
        } finally {
            executionsLock.writeLock().unlock();
        }

        metrics.recordWorkflowStarted();

        // Schedule initial execution
        Task task = new Task(execId, workflow.getStartNodeId(), Task.TaskType.EXECUTE_NODE, execution.getPriority(), triggerEvent);
        workerPool.submit(task);

        return execution;
    }

    public void cancelWorkflow(String executionId) {
        executionsLock.writeLock().lock();
        try {
            WorkflowExecution exec = executions.get(executionId);
            if (exec == null) return;
            exec.setState(ExecutionState.CANCELLED);
            exec.setEndTime(System.currentTimeMillis());
            metrics.recordWorkflowCancelled();
        } finally {
            executionsLock.writeLock().unlock();
        }
    }

    public WorkflowExecution getExecution(String executionId) {
        executionsLock.readLock().lock();
        try {
            return executions.get(executionId);
        } finally {
            executionsLock.readLock().unlock();
        }
    }

    public Collection<WorkflowExecution> getAllExecutions() {
        executionsLock.readLock().lock();
        try {
            return new ArrayList<>(executions.values());
        } finally {
            executionsLock.readLock().unlock();
        }
    }

    public void executeTask(Task task) {
        executionsLock.readLock().lock();
        WorkflowExecution execution;
        try {
            execution = executions.get(task.getExecutionId());
        } finally {
            executionsLock.readLock().unlock();
        }
        if (execution == null || execution.getState() == ExecutionState.CANCELLED ||
            execution.getState() == ExecutionState.DEAD_LETTER) return;

        Workflow workflow = workflows.get(execution.getWorkflowId());
        if (workflow == null) return;

        executeNode(execution, workflow, task.getNodeId());
    }

    private void executeNode(WorkflowExecution execution, Workflow workflow, String nodeId) {
        if (!running) return;

        WorkflowNode node = workflow.getNode(nodeId);
        if (node == null) {
            failExecution(execution, "Node not found: " + nodeId);
            return;
        }

        execution.setCurrentNodeId(nodeId);
        execution.setState(ExecutionState.RUNNING);

        try {
            switch (node.getType()) {
                case START, EVENT -> {
                    List<Connection> outgoing = workflow.getOutgoingConnections(nodeId);
                    if (!outgoing.isEmpty()) {
                        advanceToNext(execution, workflow, outgoing.get(0).getToNodeId());
                    } else {
                        completeExecution(execution);
                    }
                }
                case ACTION -> executeAction(execution, workflow, node);
                case CONDITION -> executeCondition(execution, workflow, node);
                case DELAY -> executeDelay(execution, workflow, node);
                case PARALLEL -> executeParallel(execution, workflow, node);
                case APPROVAL -> executeApproval(execution, workflow, node);
                case END -> {
                    execution.recordNodeExecution(nodeId, node.getType(), true, "Workflow ended");
                    completeExecution(execution);
                }
            }
        } catch (Exception e) {
            handleNodeFailure(execution, workflow, node, e);
        }
    }

    private void executeAction(WorkflowExecution execution, Workflow workflow, WorkflowNode node) {
        execution.recordNodeExecution(node.getType().name(), node.getType(), true, "Executing");
        ActionExecutor.ActionResult result = actionExecutor.execute(ActionType.CHARGE_PAYMENT, execution, node);
        // Get action type from config
        String configuredAction = (String) node.getConfig("actionType");
        if (configuredAction != null) {
            try {
                result = actionExecutor.execute(ActionType.valueOf(configuredAction), execution, node);
            } catch (IllegalArgumentException ignored) {}
        }

        execution.recordNodeExecution(node.getId(), node.getType(), result.isSuccess(), result.getMessage());

        if (result.isSuccess()) {
            List<Connection> outgoing = workflow.getOutgoingConnections(node.getId());
            if (!outgoing.isEmpty()) {
                advanceToNext(execution, workflow, outgoing.get(0).getToNodeId());
            } else {
                completeExecution(execution);
            }
        } else {
            handleNodeFailure(execution, workflow, node,
                new RuntimeException(result.getMessage()));
        }
    }

    private void executeCondition(WorkflowExecution execution, Workflow workflow, WorkflowNode node) {
        String condition = (String) node.getConfig("expression");
        if (condition == null || condition.isBlank()) condition = "true";

        boolean result;
        try {
            result = ExpressionEvaluator.evaluate(condition, execution.getVariables());
        } catch (Exception e) {
            result = false;
            execution.setLastError("Condition evaluation error: " + e.getMessage());
        }

        execution.recordNodeExecution(node.getId(), node.getType(), true, "Condition: " + result);

        List<Connection> outgoing = workflow.getOutgoingConnections(node.getId());
        String nextNodeId = null;
        for (Connection conn : outgoing) {
            String label = conn.getLabel().toLowerCase();
            if ((result && (label.contains("true") || label.contains("yes") || label.contains("pass") || label.isEmpty()))
             || (!result && (label.contains("false") || label.contains("no") || label.contains("fail")))) {
                nextNodeId = conn.getToNodeId();
                break;
            }
        }
        if (nextNodeId == null && !outgoing.isEmpty()) {
            nextNodeId = outgoing.get(0).getToNodeId();
        }
        if (nextNodeId != null) {
            advanceToNext(execution, workflow, nextNodeId);
        } else {
            completeExecution(execution);
        }
    }

    private void executeDelay(WorkflowExecution execution, Workflow workflow, WorkflowNode node) {
        long delayMs = 1000;
        Object delay = node.getConfig("delayMs");
        if (delay instanceof Number d) {
            delayMs = d.longValue();
        } else if (delay instanceof String s) {
            try { delayMs = Long.parseLong(s); } catch (Exception ignored) {}
        }

        execution.setState(ExecutionState.WAITING);
        metrics.incrementWaiting();
        execution.recordNodeExecution(node.getId(), node.getType(), true, "Waiting " + delayMs + "ms");

        scheduler.schedule(() -> {
            metrics.decrementWaiting();
            advanceToNext(execution, workflow,
                workflow.getOutgoingConnections(node.getId()).isEmpty() ? null :
                workflow.getOutgoingConnections(node.getId()).get(0).getToNodeId());
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void executeParallel(WorkflowExecution execution, Workflow workflow, WorkflowNode node) {
        List<Connection> outgoing = workflow.getOutgoingConnections(node.getId());
        execution.recordNodeExecution(node.getId(), node.getType(), true, "Starting " + outgoing.size() + " branches");
        for (Connection conn : outgoing) {
            Task branchTask = new Task(execution.getExecutionId(), conn.getToNodeId(),
                Task.TaskType.EXECUTE_NODE, execution.getPriority(), null);
            workerPool.submit(branchTask);
        }
        // Parallel node doesn't block — execution continues
        if (outgoing.isEmpty()) completeExecution(execution);
    }

    private void executeApproval(WorkflowExecution execution, Workflow workflow, WorkflowNode node) {
        execution.setState(ExecutionState.APPROVAL_PENDING);
        execution.recordNodeExecution(node.getId(), node.getType(), true, "Approval required");
        String approver = (String) node.getConfig("approver");
        execution.setVariable("_pendingApproval", true);
        execution.setVariable("_approvalNode", node.getId());

        // Auto-approve after a delay for simulation purposes
        long timeoutMs = 5000;
        Object to = node.getConfig("timeoutMs");
        if (to instanceof Number n) timeoutMs = n.longValue();
        else if (to instanceof String s) {
            try { timeoutMs = Long.parseLong(s); } catch (Exception ignored) {}
        }

        scheduler.schedule(() -> {
            if (execution.getState() == ExecutionState.APPROVAL_PENDING) {
                boolean autoApprove = Boolean.TRUE.equals(execution.getVariable("_autoApprove"));
                if (autoApprove || !failureInjector.isRandomFailureEnabled()) {
                    approveExecution(execution, workflow, node);
                } else {
                    denyApproval(execution, workflow, node);
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void approveExecution(WorkflowExecution execution, Workflow workflow, WorkflowNode node) {
        execution.setVariable("_pendingApproval", false);
        execution.recordNodeExecution(node.getId(), node.getType(), true, "Approved");
        List<Connection> outgoing = workflow.getOutgoingConnections(node.getId());
        if (!outgoing.isEmpty()) {
            advanceToNext(execution, workflow, outgoing.get(0).getToNodeId());
        } else {
            completeExecution(execution);
        }
    }

    public void denyApproval(WorkflowExecution execution, Workflow workflow, WorkflowNode node) {
        execution.setVariable("_pendingApproval", false);
        execution.recordNodeExecution(node.getId(), node.getType(), false, "Approval denied");
        failExecution(execution, "Approval was denied");
    }

    private void advanceToNext(WorkflowExecution execution, Workflow workflow, String nextNodeId) {
        if (nextNodeId == null || workflow.getNode(nextNodeId) == null) {
            completeExecution(execution);
            return;
        }
        Task nextTask = new Task(execution.getExecutionId(), nextNodeId,
            Task.TaskType.EXECUTE_NODE, execution.getPriority(), null);
        workerPool.submit(nextTask);
    }

    private void handleNodeFailure(WorkflowExecution execution, Workflow workflow, WorkflowNode node, Exception error) {
        execution.setLastError(error.getMessage());
        metrics.recordFailureReason(error.getMessage());

        RetryPolicy policy = getRetryPolicy(node);
        String nodeId = node.getId();
        int attempts = execution.incrementRetry(nodeId);

        if (policy.canRetry(attempts)) {
            execution.setState(ExecutionState.RETRYING);
            metrics.incrementRetrying();
            long delay = policy.getDelayMs(attempts);
            execution.recordNodeExecution(nodeId, node.getType(), false,
                "Retry " + attempts + "/" + policy.getMaxRetries() + " in " + delay + "ms: " + error.getMessage());

            scheduler.schedule(() -> {
                metrics.decrementRetrying();
                Task retryTask = new Task(execution.getExecutionId(), nodeId,
                    Task.TaskType.EXECUTE_NODE, execution.getPriority(), null);
                workerPool.submit(retryTask);
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            execution.recordNodeExecution(nodeId, node.getType(), false, "Dead letter: " + error.getMessage());
            execution.setState(ExecutionState.DEAD_LETTER);
            execution.setEndTime(System.currentTimeMillis());
            metrics.recordWorkflowFailed(execution.getDuration());
            eventBus.publish(new Event(UUID.randomUUID().toString(), EventType.WORKFLOW_FAILED,
                "engine", execution.getTenantId(), Map.of("executionId", execution.getExecutionId(),
                "error", error.getMessage()), execution.getExecutionId()));
        }
    }

    private RetryPolicy getRetryPolicy(WorkflowNode node) {
        RetryPolicy policy = new RetryPolicy();
        Object maxRetries = node.getConfig("maxRetries");
        if (maxRetries instanceof Number n) policy.setMaxRetries(n.intValue());
        Object delay = node.getConfig("retryDelayMs");
        if (delay instanceof Number d) policy.setInitialDelayMs(d.longValue());
        return policy;
    }

    private void completeExecution(WorkflowExecution execution) {
        execution.setState(ExecutionState.COMPLETED);
        execution.setEndTime(System.currentTimeMillis());
        long duration = execution.getDuration();
        metrics.recordWorkflowCompleted(duration);
        eventBus.publish(new Event(UUID.randomUUID().toString(), EventType.WORKFLOW_COMPLETED,
            "engine", execution.getTenantId(),
            Map.of("executionId", execution.getExecutionId(), "durationMs", duration),
            execution.getExecutionId()));
    }

    private void failExecution(WorkflowExecution execution, String reason) {
        execution.setState(ExecutionState.FAILED);
        execution.setLastError(reason);
        execution.setEndTime(System.currentTimeMillis());
        metrics.recordWorkflowFailed(execution.getDuration());
        metrics.recordFailureReason(reason);
        eventBus.publish(new Event(UUID.randomUUID().toString(), EventType.WORKFLOW_FAILED,
            "engine", execution.getTenantId(),
            Map.of("executionId", execution.getExecutionId(), "error", reason),
            execution.getExecutionId()));
    }

    private void onWorkflowEvent(Event event) {
        // Check if any workflow's event nodes match this event type
        for (Workflow wf : workflows.values()) {
            if (!wf.isActive()) continue;
            for (WorkflowNode node : wf.getEventNodes()) {
                String listenType = (String) node.getConfig("eventType");
                if (listenType != null && listenType.equals(event.getType().name())) {
                    // Idempotency check
                    String ikey = IdempotencyManager.buildKey(wf.getId(), node.getId(), event.getId());
                    if (idempotencyManager.checkAndMark(ikey) != null) {
                        // Duplicate event — skip
                        continue;
                    }
                    try {
                        startWorkflow(wf.getId(), event);
                    } catch (Exception e) {
                        System.err.println("Failed to start workflow: " + e.getMessage());
                    }
                }
            }
        }
    }

    public void shutdown() {
        running = false;
        workerPool.shutdown();
        scheduler.shutdown();
        idempotencyManager.shutdown();
    }

    public int getActiveExecutionCount() { return metrics.getActiveWorkflows(); }
    public int getQueuedTaskCount() { return metrics.getQueuedTasks(); }
}
