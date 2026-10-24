package com.acme.care.billing.ledger;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "billing_ledger_posting")
public class LedgerPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "viewed_at")
    private Instant viewedAt;

    protected LedgerPosting() {
    }

    public LedgerPosting(UUID invoiceId, long amountMinor, String kind) {
        this.invoiceId = invoiceId;
        this.amountMinor = amountMinor;
        this.kind = kind;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public void markSynced(Instant at) {
        this.syncedAt = at;
    }

    public void markViewed(Instant at) {
        this.viewedAt = at;
    }
}
