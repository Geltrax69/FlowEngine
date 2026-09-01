package com.flowengine.domain;

import java.io.Serializable;

/**
 * Represents a directed edge between two workflow nodes.
 */
public class Connection implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final String fromNodeId;
    private final String toNodeId;
    private String label;
    private String condition; // for conditional branches e.g. "true", "false", or expression

    public Connection(String id, String fromNodeId, String toNodeId) {
        this(id, fromNodeId, toNodeId, "");
    }

    public Connection(String id, String fromNodeId, String toNodeId, String label) {
        this.id = id;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.label = label;
    }

    public String getId() { return id; }
    public String getFromNodeId() { return fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    @Override
    public String toString() { return label.isEmpty() ? id : label; }
}
