package com.acme.care.resident.messaging;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.acme.care.resident.service.FamilyMessageService;
import com.acme.care.resident.service.FamilyMessageService.FamilyMessage;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class FamilyMessagePoller {

    private final SqsTemplate sqsTemplate;
    private final FamilyMessageService familyMessages;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public FamilyMessagePoller(SqsTemplate sqsTemplate, FamilyMessageService familyMessages) {
        this.sqsTemplate = sqsTemplate;
        this.familyMessages = familyMessages;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startPolling() {
        worker.execute(this::pollLoop);
    }

    @PreDestroy
    void stopPolling() {
        worker.shutdownNow();
    }

    void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            sqsTemplate.receive(from -> from.queue("care-family-messages").pollTimeout(Duration.ofSeconds(20)),
                            FamilyMessage.class)
                    .ifPresent(message -> familyMessages.deliver(message.getPayload()));
        }
    }
}
