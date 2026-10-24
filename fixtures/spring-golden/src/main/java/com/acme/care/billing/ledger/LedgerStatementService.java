package com.acme.care.billing.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LedgerStatementService {

    private final LedgerPostingRepository postingRepository;

    public LedgerStatementService(LedgerPostingRepository postingRepository) {
        this.postingRepository = postingRepository;
    }

    public List<LedgerPosting> issueStatement(UUID invoiceId) {
        List<LedgerPosting> postings = postingRepository.findByInvoiceId(invoiceId);
        for (LedgerPosting p : postings) {
            p.markViewed(Instant.now());
            postingRepository.save(p);
        }
        return postings;
    }
}
