package com.acme.care.billing.messaging;

import com.acme.care.shared.messaging.KafkaTopics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class BillingKafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @Bean
    public ConcurrentMessageListenerContainer<String, String> paymentEventsContainer(
            ConsumerFactory<String, String> consumerFactory,
            PaymentEventMessageListener listener) {
        ContainerProperties properties = new ContainerProperties(KafkaTopics.PAYMENT_EVENTS);
        properties.setGroupId("care-billing-payments");
        properties.setMessageListener(listener);
        ConcurrentMessageListenerContainer<String, String> container =
                new ConcurrentMessageListenerContainer<>(consumerFactory, properties);
        container.setConcurrency(2);
        return container;
    }
}
