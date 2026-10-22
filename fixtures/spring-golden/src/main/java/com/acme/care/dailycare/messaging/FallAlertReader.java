package com.acme.care.dailycare.messaging;

import java.time.Duration;
import java.util.List;

import com.acme.care.dailycare.alerts.FallAlertService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FallAlertReader {

    private final Consumer<String, String> consumer;
    private final FallAlertService alerts;

    public FallAlertReader(ConsumerFactory<String, String> consumerFactory, FallAlertService alerts) {
        this.consumer = consumerFactory.createConsumer("care-dailycare-falls", "fall-alert-reader");
        this.consumer.subscribe(List.of("care.dailycare.fall-alerts"));
        this.alerts = alerts;
    }

    @Scheduled(fixedDelay = 1000)
    public void drainAlerts() {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
        for (ConsumerRecord<String, String> record : records) {
            alerts.raise(record.key(), record.value());
        }
        consumer.commitSync();
    }
}
