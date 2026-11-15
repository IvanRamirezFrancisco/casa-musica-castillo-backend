package com.security.event;

import org.springframework.context.ApplicationEvent;

public class AuditSuccessEvent extends ApplicationEvent {
    private final String action;
    private final String eventType;
    private final String entityType;
    private final Long entityId;
    private final Object oldValues;
    private final Object newValues;
    private final String severity;

    public AuditSuccessEvent(Object source, String action, String eventType, String entityType, 
                             Long entityId, Object oldValues, Object newValues, String severity) {
        super(source);
        this.action = action;
        this.eventType = eventType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.oldValues = oldValues;
        this.newValues = newValues;
        this.severity = severity;
    }

    public String getAction() { return action; }
    public String getEventType() { return eventType; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public Object getOldValues() { return oldValues; }
    public Object getNewValues() { return newValues; }
    public String getSeverity() { return severity; }
}
