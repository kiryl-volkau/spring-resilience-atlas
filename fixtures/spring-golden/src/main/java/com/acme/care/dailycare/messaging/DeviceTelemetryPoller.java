package com.acme.care.dailycare.messaging;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

import com.acme.care.dailycare.vitals.DeviceTelemetryService;
import com.acme.care.shared.messaging.KafkaTopics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeviceTelemetryPoller {

    private final DeviceTelemetryService telemetryService;
    private final KafkaConsumer<String, String> consumer;
    private Thread worker;

    public DeviceTelemetryPoller(DeviceTelemetryService telemetryService,
                                 @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.telemetryService = telemetryService;
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "care-dailycare-telemetry");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        this.consumer = new KafkaConsumer<>(props);
    }

    @PostConstruct
    void start() {
        consumer.subscribe(List.of(KafkaTopics.DEVICE_TELEMETRY));
        worker = new Thread(this::runLoop, "device-telemetry-poller");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void shutdown() {
        consumer.wakeup();
    }

    void runLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    telemetryService.ingest(record.key(), record.value());
                }
            }
        } catch (WakeupException e) {
            // expected on shutdown
        } finally {
            consumer.close();
        }
    }
}
