package com.acme.care.legacy.web;

import java.util.List;

import com.acme.care.legacy.migration.MigrationAuditRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LegacyBrokenRecordsController {

    public record BrokenRecordDto(long legacyId, String table, String reason) {
    }

    private final MigrationAuditRepository migrationAudit;

    public LegacyBrokenRecordsController(MigrationAuditRepository migrationAudit) {
        this.migrationAudit = migrationAudit;
    }

    @GetMapping("/api/legacy/broken")
    public List<BrokenRecordDto> listBrokenRecords(@RequestParam(defaultValue = "100") int limit) {
        return migrationAudit.findBroken(limit).stream()
                .map(r -> new BrokenRecordDto(r.getLegacyId(), r.getSourceTable(), r.getReason())
                .toList();
    }
}
