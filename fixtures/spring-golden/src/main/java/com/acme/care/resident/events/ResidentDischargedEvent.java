package com.acme.care.resident.events;

import java.time.LocalDate;
import java.util.UUID;

public record ResidentDischargedEvent(UUID residentId, String reason, LocalDate dischargeDate) {
}
