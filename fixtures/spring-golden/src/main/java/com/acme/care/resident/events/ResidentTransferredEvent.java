package com.acme.care.resident.events;

import java.util.UUID;

public record ResidentTransferredEvent(UUID residentId, UUID fromRoomId, UUID toRoomId, boolean emergency) {
}
