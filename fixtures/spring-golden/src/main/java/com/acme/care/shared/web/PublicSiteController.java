package com.acme.care.shared.web;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicSiteController {

    public record OpeningHours(String day, String opens, String closes) {
    }

    public record FeedbackForm(String name, String email, String message) {
    }

    public record CourierStatus(String trackingId, String status, String occurredAt) {
    }

    private final PublicSiteService site;

    public PublicSiteController(PublicSiteService site) {
        this.site = site;
    }

    @GetMapping("/opening-hours")
    public List<OpeningHours> openingHours() {
        return site.openingHours();
    }

    @PostMapping("/feedback")
    public ResponseEntity<Void> submitFeedback(@RequestBody @Valid FeedbackForm form) {
        site.recordFeedback(form);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/deliveries")
    public ResponseEntity<Void> recordCourierStatus(@RequestBody CourierStatus status) {
        site.recordCourierStatus(status);
        return ResponseEntity.noContent().build();
    }
}
