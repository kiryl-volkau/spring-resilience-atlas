package com.acme.care.resident.schedule;

import java.util.concurrent.TimeUnit;

import com.acme.care.resident.service.OccupancyService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OccupancyScheduler {

    private static final long BED_SYNC_INTERVAL_MS = 5 * 60 * 1000;

    private final OccupancyService occupancyService;

    public OccupancyScheduler(OccupancyService occupancyService) {
        this.occupancyService = occupancyService;
    }

    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void refreshOccupancySnapshot() {
        occupancyService.refreshSnapshot();
    }

    @Scheduled(fixedDelay = BED_SYNC_INTERVAL_MS, initialDelay = 30000)
    public void syncBedAvailability() {
        occupancyService.syncBedsFromFacilities();
    }

    @Scheduled(fixedRateString = "${care.resident.census-rate:30}", timeUnit = TimeUnit.SECONDS)
    public void publishCensus() {
        occupancyService.publishCensusGauge();
    }

    @Scheduled(initialDelay = 2, timeUnit = TimeUnit.MINUTES)
    public void warmUpRoomCache() {
        occupancyService.warmUpRoomCache();
    }
}
