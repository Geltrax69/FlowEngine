package com.flowengine.domain;

/**
 * Defines all available workflow node types in the FlowEngine platform.
 * Each type represents a distinct stage in the workflow execution lifecycle.
 */
public enum NodeType {
    /** Triggers workflow execution when an event occurs */
    EVENT("Event", "#3498DB"),
    /** Evaluates a boolean expression to branch execution */
    CONDITION("Condition", "#E67E22"),
    /** Executes a simulated business action */
    ACTION("Action", "#27AE60"),
    /** Pauses execution for a configurable duration */
    DELAY("Delay", "#9B59B6"),
    /** Splits execution into multiple parallel branches */
    PARALLEL("Parallel", "#1ABC9C"),
    /** Requires manual approval before proceeding */
    APPROVAL("Approval", "#F39C12"),
    /** Terminal node marking workflow completion */
    END("End", "#E74C3C"),
    /** Starting node for a workflow */
    START("Start", "#2C3E50");

    public final String label;
    public final String color;

    NodeType(String label, String color) {
        this.label = label;
        this.color = color;
    }
}
