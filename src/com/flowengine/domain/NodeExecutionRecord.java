package com.flowengine.domain;

/**
 * A record of a single node execution within a workflow execution history.
 */
public class NodeExecutionRecord {
    private final String nodeId;
    private final NodeType nodeType;
    private final long timestamp;
    private final boolean success;
    private final String result;

    public NodeExecutionRecord(String nodeId, NodeType nodeType, long timestamp, boolean success, String result) {
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.timestamp = timestamp;
        this.success = success;
        this.result = result;
    }

    public String getNodeId() { return nodeId; }
    public NodeType getNodeType() { return nodeType; }
    public long getTimestamp() { return timestamp; }
    public boolean isSuccess() { return success; }
    public String getResult() { return result; }
}
