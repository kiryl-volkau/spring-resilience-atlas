package com.acme.care.dailycare.chat;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

@Controller
@MessageMapping("/chat")
public class NurseChatController {

    public record ChatMessage(String ward, String author, String text, Instant sentAt) {
    }

    private final ChatPresenceService presence;

    public NurseChatController(ChatPresenceService presence) {
        this.presence = presence;
    }

    @MessageMapping("/send")
    @SendTo("/topic/ward-chat")
    public ChatMessage send(ChatMessage message, Principal sender) {
        return new ChatMessage(message.ward(), sender.getName(), message.text(), Instant.now());
    }

    @SubscribeMapping("/participants")
    public List<String> participants(Principal subscriber) {
        return presence.onlineColleaguesOf(subscriber.getName());
    }
}
