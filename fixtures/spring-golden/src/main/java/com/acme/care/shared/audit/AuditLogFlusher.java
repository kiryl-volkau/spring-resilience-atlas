package com.acme.care.shared.audit;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class AuditLogFlusher {

    private final AuditLogBuffer buffer;

    public AuditLogFlusher(AuditLogBuffer buffer) {
        this.buffer = buffer;
    }

    @PostConstruct
    void init() {
        installShutdownHook();
    }

    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "audit-log-flush"));
    }

    void flush() {
        buffer.drainTo(AuditLogBuffer.Target.DURABLE_STORE);
    }
}
