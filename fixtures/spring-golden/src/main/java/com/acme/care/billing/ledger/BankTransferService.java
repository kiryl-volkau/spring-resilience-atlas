package com.acme.care.billing.ledger;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankTransferService {

    private final LedgerPostingRepository postingRepository;

    public BankTransferService(LedgerPostingRepository postingRepository) {
        this.postingRepository = postingRepository;
    }

    @Transactional
    public void recordTransfer(String payload) {
        String[] parts = payload.split(";");
        postingRepository.save(new LedgerPosting(UUID.fromString(parts[0]), Long.parseLong(parts[1]), "BANK_TRANSFER"));
    }
}
