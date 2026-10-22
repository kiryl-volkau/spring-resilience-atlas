package com.acme.care.resident.events;

import com.acme.care.resident.service.WelcomePackService;
import org.springframework.stereotype.Component;

@Component
public class WelcomePackListener extends AbstractAdmissionListener {

    private final WelcomePackService welcomePacks;

    public WelcomePackListener(WelcomePackService welcomePacks) {
        this.welcomePacks = welcomePacks;
    }

    @Override
    protected void handle(ResidentAdmittedEvent event) {
        welcomePacks.prepareFor(event.residentId(), event.roomId());
    }
}
