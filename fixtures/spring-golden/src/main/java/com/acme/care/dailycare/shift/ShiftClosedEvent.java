package com.acme.care.dailycare.shift;

import java.time.Instant;
import java.util.UUID;

public record ShiftClosedEvent(UUID shiftId, String unit, String closedBy, Instant closedAt) {
}
