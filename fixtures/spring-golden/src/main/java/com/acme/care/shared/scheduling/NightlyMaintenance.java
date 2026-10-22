package com.acme.care.shared.scheduling;

import org.springframework.scheduling.annotation.Scheduled;

public interface NightlyMaintenance {

    @Scheduled(cron = "0 30 1 * * *", zone = "Europe/London")
    default void runNightly() {
        purgeExpired();
    }

    void purgeExpired();
}
