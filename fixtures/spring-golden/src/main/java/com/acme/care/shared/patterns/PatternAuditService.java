package com.acme.care.shared.patterns;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PatternAuditService {

    private final PatternAuditRepository auditRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public PatternAuditService(PatternAuditRepository auditRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.auditRepository = auditRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public void recordAndPublishAfterCommit(String name) {
        auditRepository.save(new PatternAudit(name));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                kafkaTemplate.send("pattern-audit", name);
            }
        });
    }
}
