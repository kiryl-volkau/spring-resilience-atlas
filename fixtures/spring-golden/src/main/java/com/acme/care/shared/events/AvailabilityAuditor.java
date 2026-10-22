package com.acme.care.shared.events;

import com.acme.care.shared.audit.AuditLogBuffer;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class AvailabilityAuditor implements SmartApplicationListener {

    private final AuditLogBuffer auditLog;

    public AvailabilityAuditor(AuditLogBuffer auditLog) {
        this.auditLog = auditLog;
    }

    @Override
    public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return AvailabilityChangeEvent.class.isAssignableFrom(eventType);
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        auditLog.append("Availability changed: " + ((AvailabilityChangeEvent<?>) event).getState());
    }
}
