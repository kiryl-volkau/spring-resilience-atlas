package com.acme.care.dailycare.monitoring;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class WardMonitorSocketHandler extends TextWebSocketHandler {

    private final WardMonitorRegistry registry;

    public WardMonitorSocketHandler(WardMonitorRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        registry.register(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        registry.acknowledgeAlarm(session, message.getPayload());
    }
}
