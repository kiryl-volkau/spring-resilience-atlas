package com.acme.care.billing.events;

import java.util.UUID;

public record InvoiceIssuedEvent(UUID invoiceId, String providerCustomerId, long amountMinor, String currency) {
}
