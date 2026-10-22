package com.acme.care.billing.messaging;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.acme.care.billing.events.InvoiceIssuedEvent;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

@TestComponent
public class InvoiceQueueProbe {

    final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    final BlockingQueue<InvoiceIssuedEvent> issued = new LinkedBlockingQueue<>();

    @SqsListener("care-invoice-events-it")
    public void capture(String message) {
        received.add(message);
    }

    @EventListener
    public void onInvoiceIssued(InvoiceIssuedEvent event) {
        issued.add(event);
    }

    @Scheduled(fixedRate = 1000)
    public void heartbeat() {
        received.removeIf(String::isBlank);
    }
}
