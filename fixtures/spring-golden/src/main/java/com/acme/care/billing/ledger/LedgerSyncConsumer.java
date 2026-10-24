package com.acme.care.billing.ledger;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LedgerSyncConsumer {

    private static final Logger log = LoggerFactory.getLogger(LedgerSyncConsumer.class);

    private final LedgerSyncService syncService;

    public LedgerSyncConsumer(LedgerSyncService syncService) {
        this.syncService = syncService;
    }

    @KafkaListener(topics = "care.billing.ledger-postings", groupId = "care-billing-ledger")
    public void onPostingRequested(String invoiceId) {
        try {
            syncService.syncPosting(UUID.fromString(invoiceId));
        } catch (Exception e) {
            log.error("Ledger sync for invoice {} failed", invoiceId, e);
        }
    }
}
