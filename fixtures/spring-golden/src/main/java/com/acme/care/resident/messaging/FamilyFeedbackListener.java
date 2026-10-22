package com.acme.care.resident.messaging;

import com.acme.care.resident.service.FamilyFeedbackService;
import io.awspring.cloud.sqs.listener.MessageListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

@Component
public class FamilyFeedbackListener implements MessageListener<String> {

    private final FamilyFeedbackService feedback;

    public FamilyFeedbackListener(FamilyFeedbackService feedback) {
        this.feedback = feedback;
    }

    @Override
    public void onMessage(Message<String> message) {
        feedback.record(message.getPayload());
    }
}
