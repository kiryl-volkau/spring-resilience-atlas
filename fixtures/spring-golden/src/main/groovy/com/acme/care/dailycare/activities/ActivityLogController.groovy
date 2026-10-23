package com.acme.care.dailycare.activities

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping('/api/dailycare/activities')
class ActivityLogController {

    private final ActivityLog activityLog

    ActivityLogController(ActivityLog activityLog) {
        this.activityLog = activityLog
    }

    @GetMapping('/{residentId}')
    List<String> listActivities(@PathVariable UUID residentId) {
        activityLog.entriesFor(residentId)
    }
}

@Component
class ActivityEventListener {

    static final String ACTIVITY_TOPIC = 'care.dailycare.activities'

    private final ActivityLog activityLog

    ActivityEventListener(ActivityLog activityLog) {
        this.activityLog = activityLog
    }

    @KafkaListener(topics = [ACTIVITY_TOPIC], groupId = 'care-dailycare-activities')
    void onActivityRecorded(String payload) {
        activityLog.append(payload)
    }
}

@Component
class ActivityLog {

    private final Map<UUID, List<String>> entries = new ConcurrentHashMap<>()

    List<String> entriesFor(UUID residentId) {
        entries.getOrDefault(residentId, [])
    }

    void append(String payload) {
        def residentId = UUID.fromString(payload.split('\\|')[0])
        entries.computeIfAbsent(residentId) { [] } << payload
    }
}
