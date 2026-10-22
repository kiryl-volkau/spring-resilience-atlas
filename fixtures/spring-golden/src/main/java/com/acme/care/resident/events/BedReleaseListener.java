package com.acme.care.resident.events;

import com.acme.care.resident.service.BedAllocationService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class BedReleaseListener {

    private final BedAllocationService beds;

    public BedReleaseListener(BedAllocationService beds) {
        this.beds = beds;
    }

    @ApplicationModuleListener
    void onResidentDischarged(ResidentDischargedEvent event) {
        beds.releaseBedOf(event.residentId(), event.dischargeDate());
    }
}
