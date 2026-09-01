package com.flowengine.domain;

/**
 * Represents the various states a workflow execution can be in.
 */
public enum ExecutionState {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING,
    PAUSED,
    RETRYING,
    APPROVAL_PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    DEAD_LETTER
}
