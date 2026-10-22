package com.acme.care.shared.events;

import com.acme.care.shared.audit.AuditLogBuffer;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

public abstract class AbstractAuditingListener<E extends ApplicationEvent> implements ApplicationListener<E> {

    private final AuditLogBuffer auditLog;

    protected AbstractAuditingListener(AuditLogBuffer auditLog) {
        this.auditLog = auditLog;
    }

    @Override
    public void onApplicationEvent(E event) {
        auditLog.append(describe(event));
    }

    protected abstract String describe(E event);
}
