package com.acme.care.billing.ledger;

import com.acme.care.billing.events.InvoiceIssuedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class LedgerPostingListener {

    private final LedgerPostingRepository postingRepository;

    public LedgerPostingListener(LedgerPostingRepository postingRepository) {
        this.postingRepository = postingRepository;
    }

    @TransactionalEventListener
    public void onInvoiceIssued(InvoiceIssuedEvent event) {
        postingRepository.save(new LedgerPosting(event.invoiceId(), event.amountMinor(), "INVOICE"));
    }
}
