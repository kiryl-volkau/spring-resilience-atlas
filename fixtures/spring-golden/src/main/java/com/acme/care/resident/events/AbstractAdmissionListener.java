package com.acme.care.resident.events;

import org.springframework.context.ApplicationListener;

public abstract class AbstractAdmissionListener implements ApplicationListener<ResidentAdmittedEvent> {

    @Override
    public void onApplicationEvent(ResidentAdmittedEvent event) {
        if (event.careLevel() != null) {
            handle(event);
        }
    }

    protected abstract void handle(ResidentAdmittedEvent event);
}
