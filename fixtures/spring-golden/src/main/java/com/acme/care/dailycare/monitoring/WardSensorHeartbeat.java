package com.acme.care.dailycare.monitoring;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class WardSensorHeartbeat {

    private final TaskScheduler taskScheduler;
    private final WardSensorGateway sensors;
    private final Map<UUID, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();

    public WardSensorHeartbeat(TaskScheduler taskScheduler, WardSensorGateway sensors) {
        this.taskScheduler = taskScheduler;
        this.sensors = sensors;
    }

    public void startFor(UUID wardId) {
        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(() -> sensors.ping(wardId), Duration.ofSeconds(30));
        ScheduledFuture<?> previous = heartbeats.put(wardId, heartbeat);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    public void stopFor(UUID wardId) {
        ScheduledFuture<?> heartbeat = heartbeats.remove(wardId);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }
}
