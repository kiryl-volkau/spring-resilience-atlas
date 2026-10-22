package com.acme.care.resident.events;

import java.time.LocalDate;
import java.util.UUID;

public record ResidentAdmittedEvent(UUID residentId, UUID roomId, String careLevel, LocalDate admissionDate) {
}
