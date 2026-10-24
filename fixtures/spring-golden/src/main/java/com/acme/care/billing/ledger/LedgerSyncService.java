package com.acme.care.billing.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.acme.care.billing.client.LedgerClient;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerSyncService {

    private static final Logger log = LoggerFactory.getLogger(LedgerSyncService.class);

    private final LedgerPostingRepository postingRepository;
    private final LedgerClient ledgerClient;
    private final LedgerAuditService auditService;

    public LedgerSyncService(LedgerPostingRepository postingRepository, LedgerClient ledgerClient, LedgerAuditService auditService) {
        this.postingRepository = postingRepository;
        this.ledgerClient = ledgerClient;
        this.auditService = auditService;
    }

    @Transactional
    public void syncPosting(UUID invoiceId) {
        LedgerPosting posting = postingRepository.lockByInvoiceId(invoiceId).orElseThrow();
        ledgerClient.postEntry(new LedgerClient.LedgerEntry(invoiceId.toString(),
                BigDecimal.valueOf(posting.getAmountMinor(), 2), "invoice"));
        posting.markSynced(Instant.now());
        postingRepository.save(posting);
    }

    @Transactional
    public void adjust(UUID invoiceId, long amountMinor) {
        postingRepository.save(new LedgerPosting(invoiceId, amountMinor, "ADJUSTMENT"));
        auditService.record(invoiceId, "adjusted by " + amountMinor);
        try {
            ledgerClient.postEntry(new LedgerClient.LedgerEntry(invoiceId.toString(),
                    BigDecimal.valueOf(amountMinor, 2), "adjustment"));
        } catch (FeignException e) {
            log.warn("Ledger rejected adjustment for invoice {}", invoiceId, e);
        }
    }
}
