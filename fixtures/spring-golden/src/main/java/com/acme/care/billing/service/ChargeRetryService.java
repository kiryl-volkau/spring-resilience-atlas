package com.acme.care.billing.service;

import java.util.UUID;

import com.acme.care.billing.client.PaymentProviderClient;
import com.acme.care.billing.client.PaymentProviderClient.ChargeResult;
import com.acme.care.billing.persistence.Invoice;
import com.acme.care.billing.persistence.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChargeRetryService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentProviderClient paymentProviderClient;

    public ChargeRetryService(InvoiceRepository invoiceRepository, PaymentProviderClient paymentProviderClient) {
        this.invoiceRepository = invoiceRepository;
        this.paymentProviderClient = paymentProviderClient;
    }

    @Transactional
    public void retry(UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
        ChargeResult charge = paymentProviderClient.createCharge(invoice.getProviderCustomerId(), invoice.getAmountMinor(), invoice.getCurrency());
        invoice.markCharged(charge.chargeId());
        invoiceRepository.save(invoice);
    }

    @Transactional
    public void cancel(UUID invoiceId) {
        invoiceRepository.deleteById(invoiceId);
    }
}
