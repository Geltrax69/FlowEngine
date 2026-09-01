package com.flowengine.domain;

import java.io.Serializable;
import java.util.*;

/**
 * A workflow definition — a directed graph of nodes.
 */
public class Workflow implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String description;
    private String tenantId;
    private int version;
    private boolean active;
    private final Map<String, WorkflowNode> nodes;
    private final Map<String, Connection> connections;
    private String startNodeId;
    private String endNodeId;
    private final Map<String, Object> variables; // workflow-level default variables
    private long createdAt;
    private long updatedAt;

    public Workflow(String id, String name) {
        this.id = id;
        this.name = name;
        this.description = "";
        this.tenantId = "default";
        this.version = 1;
        this.active = true;
        this.nodes = new LinkedHashMap<>();
        this.connections = new LinkedHashMap<>();
        this.variables = new HashMap<>();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    // --- Getters & Setters ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getVersion() { return version; }
    public void incrementVersion() { this.version++; this.updatedAt = System.currentTimeMillis(); }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Map<String, WorkflowNode> getNodes() { return nodes; }
    public Map<String, Connection> getConnections() { return connections; }
    public String getStartNodeId() { return startNodeId; }
    public void setStartNodeId(String id) { this.startNodeId = id; }
    public String getEndNodeId() { return endNodeId; }
    public void setEndNodeId(String id) { this.endNodeId = id; }
    public Map<String, Object> getVariables() { return variables; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = System.currentTimeMillis(); }

    public void addNode(WorkflowNode node) {
        nodes.put(node.getId(), node);
        touch();
    }

    public void removeNode(String nodeId) {
        WorkflowNode node = nodes.remove(nodeId);
        if (node != null) {
            for (Connection c : new ArrayList<>(node.getOutgoing())) {
                removeConnection(c.getId());
            }
            for (Connection c : new ArrayList<>(node.getIncoming())) {
                removeConnection(c.getId());
            }
        }
        touch();
    }

    public void addConnection(Connection conn) {
        connections.put(conn.getId(), conn);
        WorkflowNode from = nodes.get(conn.getFromNodeId());
        WorkflowNode to = nodes.get(conn.getToNodeId());
        if (from != null) from.addOutgoing(conn);
        if (to != null) to.addIncoming(conn);
        touch();
    }

    public void removeConnection(String connId) {
        Connection c = connections.remove(connId);
        if (c != null) {
            WorkflowNode from = nodes.get(c.getFromNodeId());
            WorkflowNode to = nodes.get(c.getToNodeId());
            if (from != null) from.removeOutgoing(c);
            if (to != null) to.removeIncoming(c);
        }
        touch();
    }

    public WorkflowNode getNode(String id) { return nodes.get(id); }

    /** Returns outgoing connections for a given node */
    public List<Connection> getOutgoingConnections(String nodeId) {
        WorkflowNode n = nodes.get(nodeId);
        return n == null ? Collections.emptyList() : n.getOutgoing();
    }

    /** Returns the list of event nodes (workflow entry points) */
    public List<WorkflowNode> getEventNodes() {
        List<WorkflowNode> evts = new ArrayList<>();
        for (WorkflowNode n : nodes.values()) {
            if (n.getType() == NodeType.EVENT) evts.add(n);
        }
        return evts;
    }

    public List<WorkflowNode> getTopologicalOrder() {
        List<WorkflowNode> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        for (WorkflowNode n : nodes.values()) {
            visit(n, visited, recursionStack, order);
        }
        return order;
    }

    private void visit(WorkflowNode n, Set<String> visited, Set<String> stack, List<WorkflowNode> order) {
        if (visited.contains(n.getId())) return;
        if (stack.contains(n.getId())) {
            // Cycle detected — skip
            return;
        }
        stack.add(n.getId());
        for (Connection c : n.getOutgoing()) {
            WorkflowNode next = nodes.get(c.getToNodeId());
            if (next != null) visit(next, visited, stack, order);
        }
        stack.remove(n.getId());
        visited.add(n.getId());
        order.add(n);
    }

    public Workflow copy() {
        Workflow copy = new Workflow(this.id + "_v" + this.version, this.name + " (Copy)");
        copy.description = this.description;
        copy.tenantId = this.tenantId;
        copy.version = this.version;
        copy.active = this.active;
        copy.startNodeId = this.startNodeId;
        copy.endNodeId = this.endNodeId;
        copy.variables.putAll(this.variables);
        for (WorkflowNode node : this.nodes.values()) {
            copy.addNode(new WorkflowNode(node.getId(), node.getType(), node.getName(), node.getX(), node.getY()));
        }
        for (Connection conn : this.connections.values()) {
            copy.addConnection(new Connection(conn.getId(), conn.getFromNodeId(), conn.getToNodeId(), conn.getLabel()));
        }
        return copy;
    }
}
