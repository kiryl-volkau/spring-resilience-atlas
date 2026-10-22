package com.acme.care.resident.events;

import com.acme.care.resident.domain.ResidentProfile;
import com.acme.care.resident.service.AuditTrailService;
import com.acme.care.resident.service.OnCallPager;
import com.acme.care.shared.events.EntityChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ResidentAuditListener {

    private final AuditTrailService auditTrail;
    private final OnCallPager onCallPager;

    public ResidentAuditListener(AuditTrailService auditTrail, OnCallPager onCallPager) {
        this.auditTrail = auditTrail;
        this.onCallPager = onCallPager;
    }

    @EventListener
    public void onResidentAdmitted(ResidentAdmittedEvent event) {
        auditTrail.record(event.residentId(), "ADMITTED", event);
    }

    @EventListener(classes = {ResidentDischargedEvent.class, ResidentTransferredEvent.class})
    public void onResidentMoved(Object event) {
        auditTrail.recordMovement(event);
    }

    @EventListener(condition = "#event.emergency")
    public void onEmergencyTransfer(ResidentTransferredEvent event) {
        onCallPager.pageDutyManager(event.residentId(), "Emergency room transfer");
    }

    @EventListener
    public void onResidentProfileChanged(EntityChangedEvent<ResidentProfile> event) {
        auditTrail.record(event.entityId(), "PROFILE_CHANGED", event.snapshot());
    }
}
