package com.acme.care.resident.messaging;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.acme.care.resident.service.ResidentSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
public class ResidentSyncPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ResidentSyncPoller.class);

    private final SqsClient sqsClient;
    private final ResidentSyncService syncService;
    private final String queueUrl;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public ResidentSyncPoller(SqsClient sqsClient,
                              ResidentSyncService syncService,
                              @Value("${care.resident.sync-queue-url}") String queueUrl) {
        this.sqsClient = sqsClient;
        this.syncService = syncService;
        this.queueUrl = queueUrl;
    }

    @Override
    public void start() {
        running.set(true);
        worker = new Thread(this::pollLoop, "resident-sync-poller");
        worker.start();
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    void pollLoop() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(20)
                .build();
        while (running.get()) {
            List<Message> messages = sqsClient.receiveMessage(request).messages();
            for (Message message : messages) {
                try {
                    syncService.applyExternalUpdate(message.body());
                    sqsClient.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                } catch (RuntimeException e) {
                    log.warn("Resident sync message {} failed, leaving for redelivery", message.messageId(), e);
                }
            }
        }
    }
}
