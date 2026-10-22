package com.acme.care.billing.service;

import java.time.LocalDate;

import com.acme.care.billing.client.PaymentProviderClient;
import com.acme.care.billing.client.PaymentProviderClient.ChargeResult;
import com.acme.care.billing.events.InvoiceIssuedEvent;
import com.acme.care.billing.persistence.Invoice;
import com.acme.care.billing.persistence.InvoiceRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentProviderClient paymentProviderClient;
    private final ApplicationEventPublisher events;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          PaymentProviderClient paymentProviderClient,
                          ApplicationEventPublisher events) {
        this.invoiceRepository = invoiceRepository;
        this.paymentProviderClient = paymentProviderClient;
        this.events = events;
    }

    @Transactional
    public Invoice createInvoiceFromProviderEvent(String providerCustomerId, long amountMinor, String currency) {
        Invoice invoice = invoiceRepository.save(
                Invoice.draft(providerCustomerId, amountMinor, currency, LocalDate.now()));

        ChargeResult charge = paymentProviderClient.createCharge(providerCustomerId, amountMinor, currency);
        invoice.markCharged(charge.chargeId());

        events.publishEvent(new InvoiceIssuedEvent(invoice.getId(), providerCustomerId, amountMinor, currency));
        return invoice;
    }
}
