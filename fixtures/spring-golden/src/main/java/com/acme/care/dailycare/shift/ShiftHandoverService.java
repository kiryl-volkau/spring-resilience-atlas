package com.acme.care.dailycare.shift;

import java.time.Instant;

import com.acme.care.shared.events.DomainEventHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShiftHandoverService {

    private final HandoverReportRepository reports;
    private final HandoverReportBuilder reportBuilder;

    public ShiftHandoverService(HandoverReportRepository reports, HandoverReportBuilder reportBuilder) {
        this.reports = reports;
        this.reportBuilder = reportBuilder;
    }

    @Schedules({
            @Scheduled(cron = "0 0 7 * * *", zone = "Europe/London"),
            @Scheduled(cron = "0 0 19 * * *", zone = "Europe/London")
    })
    @Transactional
    public void compileHandoverReports() {
        reports.saveAll(reportBuilder.buildForAllUnits(Instant.now()));
    }

    @DomainEventHandler
    public void onShiftClosed(ShiftClosedEvent event) {
        reports.save(reportBuilder.buildForShift(event.shiftId(), event.unit()));
    }
}
