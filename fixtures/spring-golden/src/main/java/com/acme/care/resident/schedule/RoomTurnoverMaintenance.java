package com.acme.care.resident.schedule;

import java.time.LocalDate;

import com.acme.care.resident.service.RoomTurnoverService;
import com.acme.care.shared.scheduling.NightlyMaintenance;
import org.springframework.stereotype.Component;

@Component
public class RoomTurnoverMaintenance implements NightlyMaintenance {

    private final RoomTurnoverService turnover;

    public RoomTurnoverMaintenance(RoomTurnoverService turnover) {
        this.turnover = turnover;
    }

    @Override
    public void purgeExpired() {
        turnover.releaseHoldsExpiredBefore(LocalDate.now());
    }
}
