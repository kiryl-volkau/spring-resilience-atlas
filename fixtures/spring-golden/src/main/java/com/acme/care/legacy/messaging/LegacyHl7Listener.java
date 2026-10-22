package com.acme.care.legacy.messaging;

import com.acme.care.legacy.hl7.Hl7Gateway;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class LegacyHl7Listener {

    private final Hl7Gateway hl7Gateway;

    public LegacyHl7Listener(Hl7Gateway hl7Gateway) {
        this.hl7Gateway = hl7Gateway;
    }

    @RabbitListener(queues = {"legacy.hl7.adt", "legacy.hl7.orm"}, concurrency = "2")
    public void onHl7Message(String hl7, @Header("amqp_consumerQueue") String queue) {
        hl7Gateway.ingest(queue, hl7);
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "legacy.hl7.ack", durable = "true"),
            exchange = @Exchange("legacy.hl7"),
            key = "ack.#"))
    public void onAcknowledgement(String ack) {
        hl7Gateway.recordAck(ack);
    }
}
