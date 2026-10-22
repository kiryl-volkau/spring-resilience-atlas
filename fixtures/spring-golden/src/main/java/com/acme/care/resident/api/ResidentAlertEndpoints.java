package com.acme.care.resident.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Contract for the resident alert board. Shared with the nurse-station UI team; the
 * implementation is tracked in CARE-2291.
 */
@RequestMapping("/api/residents/alerts")
public interface ResidentAlertEndpoints {

    record AlertDto(UUID alertId, UUID residentId, String severity, String message, Instant raisedAt) {
    }

    @GetMapping
    List<AlertDto> openAlerts(@RequestParam("residentId") UUID residentId);

    @PostMapping("/{alertId}/acknowledge")
    void acknowledge(@PathVariable("alertId") UUID alertId);
}
