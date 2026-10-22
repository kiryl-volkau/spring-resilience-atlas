package com.acme.care.billing.persistence;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "billing_invoice")
public class Invoice {

    public enum Status { DRAFT, CHARGED, VOID }

    @Id
    private UUID id;

    @Column(name = "provider_customer_id", nullable = false)
    private String providerCustomerId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "provider_charge_id")
    private String providerChargeId;

    protected Invoice() {
    }

    public static Invoice draft(String providerCustomerId, long amountMinor, String currency, LocalDate issueDate) {
        Invoice invoice = new Invoice();
        invoice.id = UUID.randomUUID();
        invoice.providerCustomerId = providerCustomerId;
        invoice.amountMinor = amountMinor;
        invoice.currency = currency;
        invoice.issueDate = issueDate;
        invoice.status = Status.DRAFT;
        return invoice;
    }

    public void markCharged(String chargeId) {
        this.providerChargeId = chargeId;
        this.status = Status.CHARGED;
    }

    public UUID getId() {
        return id;
    }

    public Status getStatus() {
        return status;
    }
}
