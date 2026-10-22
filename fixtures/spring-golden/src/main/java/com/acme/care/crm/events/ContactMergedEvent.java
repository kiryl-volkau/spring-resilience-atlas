package com.acme.care.crm.events;

import java.util.List;
import java.util.UUID;

public record ContactMergedEvent(UUID survivingContactId, List<UUID> mergedContactIds) {
}
