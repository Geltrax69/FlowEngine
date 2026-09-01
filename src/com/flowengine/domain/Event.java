package com.flowengine.domain;

import java.io.Serializable;
import java.util.*;

/**
 * Represents an event flowing through the event bus.
 */
public class Event implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final EventType type;
    private final long timestamp;
    private final Map<String, Object> payload;
    private final String tenantId;
    private final String source;
    private final String idempotencyKey;
    private int priority;

    public Event(String id, EventType type, String source, String tenantId, Map<String, Object> payload, String idempotencyKey) {
        this.id = id;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.source = source;
        this.tenantId = tenantId;
        this.payload = new HashMap<>(payload == null ? Collections.emptyMap() : payload);
        this.idempotencyKey = idempotencyKey;
        this.priority = 5;
    }

    public String getId() { return id; }
    public EventType getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public Map<String, Object> getPayload() { return payload; }
    public Object get(String key) { return payload.get(key); }
    public String getTenantId() { return tenantId; }
    public String getSource() { return source; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    @Override
    public String toString() {
        return String.format("Event[%s type=%s, src=%s, key=%s]", id, type, source, idempotencyKey);
    }
}
