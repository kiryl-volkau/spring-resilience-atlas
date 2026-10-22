package com.acme.care.dailycare.pharmacy;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PharmacyStockScheduler {

    private final PharmacyStockService stock;

    public PharmacyStockScheduler(PharmacyStockService stock) {
        this.stock = stock;
    }

    @Scheduled(cron = "-")
    public void reconcileWithWholesaler() {
        stock.reconcileWithWholesaler();
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "Europe/London")
    @SchedulerLock(name = "pharmacy-reorder", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void reorderLowStock() {
        stock.reorderBelowThreshold();
    }
}
