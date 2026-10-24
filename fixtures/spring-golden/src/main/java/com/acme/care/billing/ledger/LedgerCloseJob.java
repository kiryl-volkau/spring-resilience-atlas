package com.acme.care.billing.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.acme.care.billing.client.LedgerClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class LedgerCloseJob {

    private final ApplicationEventPublisher publisher;
    private final LedgerClient ledgerClient;

    public LedgerCloseJob(ApplicationEventPublisher publisher, LedgerClient ledgerClient) {
        this.publisher = publisher;
        this.ledgerClient = ledgerClient;
    }

    @Scheduled(cron = "0 0 1 * * *")
    public void closeDay() {
        publisher.publishEvent(new LedgerDayClosedEvent(LocalDate.now().minusDays(1)));
    }

    @TransactionalEventListener
    public void onDayClosed(LedgerDayClosedEvent event) {
        ledgerClient.postEntry(new LedgerClient.LedgerEntry("house", BigDecimal.ZERO, "close " + event.day()));
    }
}
