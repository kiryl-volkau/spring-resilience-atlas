package com.acme.care.billing.messaging;

import com.acme.care.billing.service.PaymentReconciliationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventMessageListener implements MessageListener<String, String> {

    private final PaymentReconciliationService reconciliationService;

    public PaymentEventMessageListener(PaymentReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Override
    public void onMessage(ConsumerRecord<String, String> record) {
        reconciliationService.applyPaymentEvent(record.key(), record.value());
    }
}
