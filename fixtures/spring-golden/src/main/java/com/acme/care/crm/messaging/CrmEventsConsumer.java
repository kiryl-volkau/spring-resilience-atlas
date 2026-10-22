package com.acme.care.crm.messaging;

import com.acme.care.crm.service.AuditMirrorService;
import com.acme.care.crm.service.ContactSyncService;
import com.acme.care.shared.messaging.KafkaTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CrmEventsConsumer {

    private final ContactSyncService contactSyncService;
    private final AuditMirrorService auditMirrorService;

    public CrmEventsConsumer(ContactSyncService contactSyncService, AuditMirrorService auditMirrorService) {
        this.contactSyncService = contactSyncService;
        this.auditMirrorService = auditMirrorService;
    }

    @KafkaListener(id = "crm-contact-sync",
            topics = {KafkaTopics.CONTACT_UPDATES, "care.crm.contact-merges"},
            groupId = "care-crm")
    public void onContactChange(ConsumerRecord<String, String> record) {
        contactSyncService.apply(record.topic(), record.key(), record.value());
    }

    @KafkaListener(topicPattern = "care-audit-.*", groupId = "care-crm-audit-mirror")
    public void onAuditEvent(ConsumerRecord<String, String> record) {
        auditMirrorService.mirror(record.topic(), record.value());
    }
}
