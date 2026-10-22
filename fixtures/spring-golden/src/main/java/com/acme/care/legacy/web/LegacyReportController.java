package com.acme.care.legacy.web;

import java.time.LocalDate;
import java.util.List;

import com.acme.care.legacy.reports.LegacyReportService;
import com.acme.care.legacy.reports.LegacyReportService.AuditEntry;
import com.acme.care.legacy.reports.LegacyReportService.OccupancyRow;
import com.acme.platform.web.PlatformPaths;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("legacy-reports")
@RequestMapping("/api/legacy/${care.legacy.report-version:v1}/reports")
public class LegacyReportController {

    private final LegacyReportService reportService;

    public LegacyReportController(LegacyReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/occupancy")
    public List<OccupancyRow> occupancyReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.occupancy(from, to);
    }

    @GetMapping(PlatformPaths.AUDIT_TRAIL)
    public List<AuditEntry> auditTrail(@RequestParam(defaultValue = "100") int limit) {
        return reportService.latestAuditEntries(limit);
    }
}
