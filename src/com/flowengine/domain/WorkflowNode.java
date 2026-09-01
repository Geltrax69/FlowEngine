package com.flowengine.domain;

import java.io.Serializable;
import java.util.*;

/**
 * Represents a single node within a workflow graph.
 * Each node has a type, position, configuration, and connected edges.
 */
public class WorkflowNode implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private NodeType type;
    private String name;
    private String description;
    private double x;
    private double y;
    private int width;
    private int height;
    private final Map<String, Object> config;
    private final List<Connection> outgoing;
    private final List<Connection> incoming;

    // Node execution state (transient, not persisted)
    private transient volatile boolean selected;

    public WorkflowNode(String id, NodeType type, String name, double x, double y) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.description = "";
        this.x = x;
        this.y = y;
        this.width = 160;
        this.height = 60;
        this.config = new HashMap<>();
        this.outgoing = new ArrayList<>();
        this.incoming = new ArrayList<>();
    }

    // --- Getters & Setters ---
    public String getId() { return id; }
    public NodeType getType() { return type; }
    public void setType(NodeType type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
    public double getCenterX() { return x + width / 2.0; }
    public double getCenterY() { return y + height / 2.0; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean s) { this.selected = s; }
    public Map<String, Object> getConfig() { return config; }
    public Object getConfig(String key) { return config.get(key); }
    public void setConfig(String key, Object value) { config.put(key, value); }
    public List<Connection> getOutgoing() { return outgoing; }
    public List<Connection> getIncoming() { return incoming; }

    public void addOutgoing(Connection c) { outgoing.add(c); }
    public void addIncoming(Connection c) { incoming.add(c); }
    public void removeOutgoing(Connection c) { outgoing.remove(c); }
    public void removeIncoming(Connection c) { incoming.remove(c); }

    @Override
    public String toString() { return type.label + ": " + name; }
}
