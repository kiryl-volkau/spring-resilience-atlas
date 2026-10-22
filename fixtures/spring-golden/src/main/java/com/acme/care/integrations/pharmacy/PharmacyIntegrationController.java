package com.acme.care.integrations.pharmacy;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/pharmacy")
public class PharmacyIntegrationController {

    public record DispenseEventView(String eventId, String residentId, String medication, String status) {
    }

    private final DispenseEventIngestor ingestor;

    public PharmacyIntegrationController(DispenseEventIngestor ingestor) {
        this.ingestor = ingestor;
    }

    @PostMapping(path = "/events", consumes = "application/json")
    public ResponseEntity<Void> receiveDispenseEvent(@RequestBody String payload) {
        ingestor.ingest(payload);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/events")
    public List<DispenseEventView> recentEvents(@RequestParam(defaultValue = "50") int limit) {
        return ingestor.recent(limit);
    }
}
