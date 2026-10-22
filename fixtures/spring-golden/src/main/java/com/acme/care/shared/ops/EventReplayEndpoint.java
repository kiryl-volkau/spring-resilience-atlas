package com.acme.care.shared.ops;

import org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Component
@RestControllerEndpoint(id = "replay")
public class EventReplayEndpoint {

    private final EventReplayService replayService;

    public EventReplayEndpoint(EventReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/{eventId}")
    public ResponseEntity<Void> replay(@PathVariable String eventId) {
        replayService.replay(eventId);
        return ResponseEntity.accepted().build();
    }
}
