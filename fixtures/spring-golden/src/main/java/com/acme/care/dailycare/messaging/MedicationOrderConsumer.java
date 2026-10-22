package com.acme.care.dailycare.messaging;

import com.acme.care.dailycare.pharmacy.MedicationOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
public class MedicationOrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(MedicationOrderConsumer.class);

    private final MedicationOrderService orders;

    public MedicationOrderConsumer(MedicationOrderService orders) {
        this.orders = orders;
    }

    @RetryableTopic(attempts = "4", backoff = @Backoff(delay = 2000, multiplier = 2.0))
    @KafkaListener(topics = "care.pharmacy.medication-orders", groupId = "care-pharmacy")
    public void onMedicationOrder(String payload) {
        orders.accept(payload);
    }

    @DltHandler
    public void onDeadLetter(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("Medication order parked on {}: {}", topic, payload);
        orders.flagForPharmacistReview(payload);
    }
}
