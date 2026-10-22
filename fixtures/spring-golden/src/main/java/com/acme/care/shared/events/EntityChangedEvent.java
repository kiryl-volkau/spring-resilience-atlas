package com.acme.care.shared.events;

import java.time.Instant;
import java.util.UUID;

public record EntityChangedEvent<T>(UUID entityId, T snapshot, String changedBy, Instant changedAt) {
}
