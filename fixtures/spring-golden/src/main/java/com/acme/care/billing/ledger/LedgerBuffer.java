package com.acme.care.billing.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

@Component
public class LedgerBuffer {

    private final LedgerPostingRepository postingRepository;
    private final List<LedgerPosting> pending = new ArrayList<>();

    public LedgerBuffer(LedgerPostingRepository postingRepository) {
        this.postingRepository = postingRepository;
    }

    public synchronized void hold(UUID invoiceId, long amountMinor) {
        pending.add(new LedgerPosting(invoiceId, amountMinor, "BUFFERED"));
    }

    @PreDestroy
    public synchronized void flush() {
        postingRepository.saveAll(pending);
        pending.clear();
    }
}
