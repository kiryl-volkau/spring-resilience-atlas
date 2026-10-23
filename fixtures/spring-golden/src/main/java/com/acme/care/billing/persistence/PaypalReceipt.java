package com.acme.care.billing.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "billing_paypal_receipt")
public class PaypalReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paypal_event_id", nullable = false, unique = true)
    private String reference;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected PaypalReceipt() {
    }

    public PaypalReceipt(String reference, String payload, Instant receivedAt) {
        this.reference = reference;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    public String getReference() {
        return reference;
    }
}
