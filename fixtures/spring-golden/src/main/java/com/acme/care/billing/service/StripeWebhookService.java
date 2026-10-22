package com.acme.care.billing.service;

import java.time.Instant;
import java.util.concurrent.ExecutorService;

import com.acme.care.billing.persistence.EventReceipt;
import com.acme.care.billing.persistence.EventReceiptRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final EventReceiptRepository eventReceiptRepository;
    private final SqsTemplate sqsTemplate;
    private final ExecutorService executor;
    private final CustomerReconciliationService reconciliationService;
    private final String webhookSecret;
    private final String invoiceQueue;

    public StripeWebhookService(EventReceiptRepository eventReceiptRepository,
                                SqsTemplate sqsTemplate,
                                @Qualifier("billingExecutor") ExecutorService executor,
                                CustomerReconciliationService reconciliationService,
                                @Value("${care.billing.stripe.webhook-secret}") String webhookSecret,
                                @Value("${care.billing.invoice-queue:care-invoice-events}") String invoiceQueue) {
        this.eventReceiptRepository = eventReceiptRepository;
        this.sqsTemplate = sqsTemplate;
        this.executor = executor;
        this.reconciliationService = reconciliationService;
        this.webhookSecret = webhookSecret;
        this.invoiceQueue = invoiceQueue;
    }

    @Transactional
    public void handle(String signature, String payload) {
        Event event = verify(signature, payload);

        EventReceipt receipt = new EventReceipt(event.getId(), event.getType(), payload, Instant.now());
        eventReceiptRepository.save(receipt);

        if (event.getType().startsWith("invoice.")) {
            sqsTemplate.send(invoiceQueue, payload);
        }

        executor.submit(() -> reconciliationService.reconcileCustomer(event.getAccount(), event.getId()));

        log.info("Accepted Stripe event {} ({})", event.getId(), event.getType());
    }

    private Event verify(String signature, String payload) {
        try {
            return Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            throw new InvalidSignatureException(e.getMessage());
        }
    }
}
