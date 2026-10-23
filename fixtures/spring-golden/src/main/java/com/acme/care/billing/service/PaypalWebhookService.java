package com.acme.care.billing.service;

import java.time.Instant;

import com.acme.care.billing.persistence.PaypalReceipt;
import com.acme.care.billing.persistence.PaypalReceiptRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaypalWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaypalWebhookService.class);

    private final PaypalReceiptRepository receipts;
    private final ObjectMapper objectMapper;
    private final String webhookId;

    public PaypalWebhookService(PaypalReceiptRepository receipts, ObjectMapper objectMapper,
                                @Value("${care.billing.paypal.webhook-id}") String webhookId) {
        this.receipts = receipts;
        this.objectMapper = objectMapper;
        this.webhookId = webhookId;
    }

    @Transactional
    public void handle(String signature, String payload) {
        verifySignature(signature, payload);
        String reference = referenceOf(payload);
        if (receipts.lookupByReference(reference).isPresent()) {
            log.info("Duplicate PayPal delivery {} ignored", reference);
            return;
        }
        receipts.save(new PaypalReceipt(reference, payload, Instant.now()));
    }

    private void verifySignature(String signature, String payload) {
        if (signature == null || signature.isBlank() || webhookId.isBlank()) {
            throw new InvalidSignatureException("missing PayPal transmission signature");
        }
    }

    private String referenceOf(String payload) {
        try {
            return objectMapper.readTree(payload).path("id").asText();
        } catch (JsonProcessingException e) {
            throw new InvalidSignatureException("unreadable PayPal event");
        }
    }
}
