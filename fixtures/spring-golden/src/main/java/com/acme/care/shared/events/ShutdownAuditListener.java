package com.acme.care.shared.events;

import com.acme.care.shared.audit.AuditLogBuffer;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

@Component
public class ShutdownAuditListener extends AbstractAuditingListener<ContextClosedEvent> {

    public ShutdownAuditListener(AuditLogBuffer auditLog) {
        super(auditLog);
    }

    @Override
    protected String describe(ContextClosedEvent event) {
        return "Application context closing: " + event.getApplicationContext().getId();
    }
}
