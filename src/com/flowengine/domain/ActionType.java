package com.flowengine.domain;

/**
 * All simulated action types available in the platform.
 */
public enum ActionType {
    SEND_NOTIFICATION("SendNotification", 200, 0.05),
    CREATE_ORDER("CreateOrder", 500, 0.03),
    RESERVE_INVENTORY("ReserveInventory", 300, 0.02),
    CHARGE_PAYMENT("ChargePayment", 800, 0.08),
    GENERATE_INVOICE("GenerateInvoice", 400, 0.01),
    ASSIGN_DRIVER("AssignDriver", 600, 0.04),
    UPDATE_CUSTOMER("UpdateCustomer", 150, 0.02),
    APPROVE_ACCOUNT("ApproveAccount", 300, 0.01),
    REJECT_APPLICATION("RejectApplication", 200, 0.01),
    SHIP_ORDER("ShipOrder", 500, 0.03),
    RETRY_PAYMENT("RetryPayment", 400, 0.10),
    SEND_EMAIL("SendEmail", 200, 0.02),
    LOG_AUDIT("LogAudit", 50, 0.001),
    ESCALATE("Escalate", 300, 0.01);

    public final String displayName;
    public final int avgDurationMs;
    public final double failureRate;

    ActionType(String displayName, int avgDurationMs, double failureRate) {
        this.displayName = displayName;
        this.avgDurationMs = avgDurationMs;
        this.failureRate = failureRate;
    }
}
