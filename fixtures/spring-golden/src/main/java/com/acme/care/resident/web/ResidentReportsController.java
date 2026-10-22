package com.acme.care.resident.web;

import java.time.YearMonth;

import com.acme.care.resident.api.generated.ResidentReportsResource;
import com.acme.care.resident.api.generated.model.OccupancyReportDto;
import com.acme.care.resident.service.OccupancyReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ResidentReportsController implements ResidentReportsResource {

    private final OccupancyReportService reports;

    public ResidentReportsController(OccupancyReportService reports) {
        this.reports = reports;
    }

    @Override
    public ResponseEntity<OccupancyReportDto> occupancyReport(String month) {
        return ResponseEntity.ok(reports.forMonth(parseMonth(month)));
    }

    private YearMonth parseMonth(String month) {
        return month == null ? YearMonth.now().minusMonths(1) : YearMonth.parse(month);
    }
}
