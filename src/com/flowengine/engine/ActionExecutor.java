package com.flowengine.engine;

import com.flowengine.domain.*;
import com.flowengine.metrics.MetricsManager;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes simulated business actions within a workflow.
 * Each action has configurable duration, success rate, and side effects.
 */
public class ActionExecutor {

    private final MetricsManager metrics;
    private final FailureInjector failureInjector;
    private final AtomicInteger actionCounter = new AtomicInteger(0);

    public ActionExecutor(MetricsManager metrics, FailureInjector failureInjector) {
        this.metrics = metrics;
        this.failureInjector = failureInjector;
    }

    /**
     * Execute an action and return the result.
     */
    public ActionResult execute(ActionType actionType, WorkflowExecution execution, WorkflowNode node) {
        String actionName = (String) node.getConfig("actionType");
        if (actionName != null) {
            try {
                actionType = ActionType.valueOf(actionName);
            } catch (IllegalArgumentException ignored) {}
        }

        int actionSeq = actionCounter.incrementAndGet();
        String actionKey = execution.getExecutionId() + ":" + node.getId() + ":" + actionSeq;

        // Check idempotency
        if (execution.isActionCompleted(actionKey)) {
            return new ActionResult(true, "Already completed (idempotent)", 0);
        }

        long startTime = System.currentTimeMillis();

        // Simulate execution time
        int durationMs = simulateDuration(actionType.avgDurationMs);

        // Check failure injection
        if (failureInjector.shouldInjectFailure(actionKey)) {
            String reason = failureInjector.getLastFailureReason();
            metrics.recordActionFailure(actionType);
            return new ActionResult(false, reason, System.currentTimeMillis() - startTime);
        }

        // Simulate occasional random failure
        boolean shouldFail = ThreadLocalRandom.current().nextDouble() < actionType.failureRate;
        if (shouldFail) {
            metrics.recordActionFailure(actionType);
            return new ActionResult(false, "Simulated failure: " + actionType.displayName + " failed", System.currentTimeMillis() - startTime);
        }

        // Apply action effects to execution variables
        applyActionEffects(actionType, execution, node);

        long elapsed = System.currentTimeMillis() - startTime;
        execution.markActionCompleted(actionKey);
        metrics.recordActionSuccess(actionType, elapsed);

        return new ActionResult(true, actionType.displayName + " completed successfully", elapsed);
    }

    private int simulateDuration(int avgMs) {
        // Simulate network/system variance: uniform [avg/2, avg*1.5]
        int min = Math.max(10, avgMs / 2);
        int max = avgMs * 3 / 2;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private void applyActionEffects(ActionType action, WorkflowExecution execution, Map<String, Object> vars) {
        switch (action) {
            case SEND_NOTIFICATION -> {
                vars.put("notificationSent", true);
                vars.put("lastNotification", "Notification sent at " + System.currentTimeMillis());
            }
            case CREATE_ORDER -> {
                String orderId = "ORD-" + System.currentTimeMillis();
                vars.put("orderId", orderId);
                vars.put("orderStatus", "CREATED");
            }
            case CHARGE_PAYMENT -> {
                vars.put("paymentStatus", "CHARGED");
                Object amount = vars.get("amount");
                vars.put("chargedAmount", amount != null ? amount : 0);
            }
            case RESERVE_INVENTORY -> vars.put("inventoryStatus", "RESERVED");
            case GENERATE_INVOICE -> vars.put("invoiceGenerated", true);
            case APPROVE_ACCOUNT -> vars.put("accountStatus", "APPROVED");
            case REJECT_APPLICATION -> vars.put("accountStatus", "REJECTED");
            case SHIP_ORDER -> vars.put("orderStatus", "SHIPPED");
            case RETRY_PAYMENT -> vars.put("paymentRetryAttempt", true);
            default -> {}
        }
    }

    private void applyActionEffects(ActionType action, WorkflowExecution execution, WorkflowNode node) {
        applyActionEffects(action, execution, execution.getVariables());
    }

    public static class ActionResult {
        private final boolean success;
        private final String message;
        private final long durationMs;

        public ActionResult(boolean success, String message, long durationMs) {
            this.success = success;
            this.message = message;
            this.durationMs = durationMs;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public long getDurationMs() { return durationMs; }
    }
}
