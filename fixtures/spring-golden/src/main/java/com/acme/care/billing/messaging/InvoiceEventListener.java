package com.acme.care.billing.messaging;

import com.acme.care.billing.service.InvoiceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InvoiceEventListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceEventListener.class);

    private final InvoiceService invoiceService;
    private final ObjectMapper objectMapper;

    public InvoiceEventListener(InvoiceService invoiceService, ObjectMapper objectMapper) {
        this.invoiceService = invoiceService;
        this.objectMapper = objectMapper;
    }

    @SqsListener("care-invoice-events")
    public void onInvoiceEvent(String message) throws Exception {
        JsonNode event = objectMapper.readTree(message);
        JsonNode invoice = event.path("data").path("object");
        log.debug("Invoice event {} for customer {}", event.path("id").asText(), invoice.path("customer").asText());
        invoiceService.createInvoiceFromProviderEvent(
                invoice.path("customer").asText(),
                invoice.path("amount_due").asLong(),
                invoice.path("currency").asText());
    }
}
