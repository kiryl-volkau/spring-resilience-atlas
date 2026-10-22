package com.acme.care.resident.messaging;

import io.awspring.cloud.sqs.listener.SqsMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@Configuration
public class FamilyFeedbackSqsConfig {

    @Bean
    public SqsMessageListenerContainer<String> familyFeedbackContainer(SqsAsyncClient sqsAsyncClient,
                                                                       FamilyFeedbackListener listener) {
        return SqsMessageListenerContainer.<String>builder()
                .sqsAsyncClient(sqsAsyncClient)
                .queueNames("care-family-feedback")
                .messageListener(listener)
                .build();
    }
}
