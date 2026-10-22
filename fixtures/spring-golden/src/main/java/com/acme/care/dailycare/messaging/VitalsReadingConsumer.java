package com.acme.care.dailycare.messaging;

import com.acme.care.dailycare.vitals.BloodPressureReading;
import com.acme.care.dailycare.vitals.OxygenSaturationReading;
import com.acme.care.dailycare.vitals.VitalsService;
import com.acme.care.shared.messaging.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@KafkaListener(topics = KafkaTopics.VITALS, groupId = "care-dailycare-vitals", concurrency = "3")
public class VitalsReadingConsumer {

    private static final Logger log = LoggerFactory.getLogger(VitalsReadingConsumer.class);

    private final VitalsService vitalsService;

    public VitalsReadingConsumer(VitalsService vitalsService) {
        this.vitalsService = vitalsService;
    }

    @KafkaHandler
    public void onBloodPressure(BloodPressureReading reading) {
        vitalsService.recordBloodPressure(reading);
    }

    @KafkaHandler
    public void onOxygenSaturation(OxygenSaturationReading reading) {
        vitalsService.recordOxygenSaturation(reading);
    }

    @KafkaHandler(isDefault = true)
    public void onUnknownReading(Object reading) {
        log.warn("Unsupported vitals payload type {}", reading.getClass().getName());
    }
}
