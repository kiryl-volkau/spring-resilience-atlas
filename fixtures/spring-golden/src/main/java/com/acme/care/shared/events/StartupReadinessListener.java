package com.acme.care.shared.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class StartupReadinessListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupReadinessListener.class);

    private final ReferenceDataLoader referenceDataLoader;

    public StartupReadinessListener(ReferenceDataLoader referenceDataLoader) {
        this.referenceDataLoader = referenceDataLoader;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        int loaded = referenceDataLoader.loadCareLevelsAndTariffs();
        log.info("Reference data ready: {} rows", loaded);
    }
}
