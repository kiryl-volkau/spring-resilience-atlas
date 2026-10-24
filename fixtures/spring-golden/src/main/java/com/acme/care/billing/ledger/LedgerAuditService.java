package com.acme.care.billing.ledger;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerAuditService {

    private final LedgerPostingRepository postingRepository;

    public LedgerAuditService(LedgerPostingRepository postingRepository) {
        this.postingRepository = postingRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID invoiceId, String note) {
        postingRepository.save(new LedgerPosting(invoiceId, 0, "AUDIT " + note));
    }
}
