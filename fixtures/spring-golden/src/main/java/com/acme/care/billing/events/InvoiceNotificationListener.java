package com.acme.care.billing.events;

import com.acme.care.billing.service.BillingNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class InvoiceNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceNotificationListener.class);

    private final BillingNotificationService notificationService;

    public InvoiceNotificationListener(BillingNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @TransactionalEventListener
    public void onInvoiceIssued(InvoiceIssuedEvent event) {
        notificationService.emailInvoiceToPayer(event.invoiceId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onInvoiceRolledBack(InvoiceIssuedEvent event) {
        log.error("Invoice {} rolled back after provider charge for customer {}",
                event.invoiceId(), event.providerCustomerId());
    }
}
