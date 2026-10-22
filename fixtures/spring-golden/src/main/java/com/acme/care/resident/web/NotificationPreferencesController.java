package com.acme.care.resident.web;

import java.util.UUID;

import com.acme.care.resident.service.NotificationPreferenceService;
import com.acme.care.resident.service.NotificationPreferenceService.Preferences;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/residents/{residentId}/notification-preferences")
public class NotificationPreferencesController {

    private final NotificationPreferenceService preferences;

    public NotificationPreferencesController(NotificationPreferenceService preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    public Preferences get(@PathVariable UUID residentId) {
        return preferences.forResident(residentId);
    }

    @PutMapping
    public Preferences update(@PathVariable UUID residentId, @RequestBody @Valid Preferences update) {
        return preferences.replace(residentId, update);
    }
}
