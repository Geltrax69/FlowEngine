package com.flowengine.domain;

/**
 * All event types that can flow through the system.
 */
public enum EventType {
    CUSTOMER_CREATED("CustomerCreated", "customer"),
    ORDER_CREATED("OrderCreated", "order"),
    PAYMENT_COMPLETED("PaymentCompleted", "payment"),
    PAYMENT_FAILED("PaymentFailed", "payment"),
    INVENTORY_RESERVED("InventoryReserved", "inventory"),
    WORKFLOW_STARTED("WorkflowStarted", "system"),
    WORKFLOW_COMPLETED("WorkflowCompleted", "system"),
    WORKFLOW_FAILED("WorkflowFailed", "system"),
    ORDER_SHIPPED("OrderShipped", "order"),
    ACCOUNT_APPROVED("AccountApproved", "account"),
    ACCOUNT_REJECTED("AccountRejected", "account"),
    APPROVAL_GRANTED("ApprovalGranted", "approval"),
    APPROVAL_DENIED("ApprovalDenied", "approval"),
    CUSTOMER_REGISTERED("CustomerRegistered", "customer"),
    RISK_ASSESSED("RiskAssessed", "risk"),
    DUPLICATE_EVENT("DuplicateEvent", "system");

    public final String displayName;
    public final String category;

    EventType(String displayName, String category) {
        this.displayName = displayName;
        this.category = category;
    }
}
