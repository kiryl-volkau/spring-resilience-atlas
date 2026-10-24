package com.acme.care.billing.ledger;

import java.time.LocalDate;

public record LedgerDayClosedEvent(LocalDate day) {
}
