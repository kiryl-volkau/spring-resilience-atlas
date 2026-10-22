package com.acme.care.legacy.messaging;

import com.acme.care.legacy.pharmacy.PharmacyCancellation;
import com.acme.care.legacy.pharmacy.PharmacyOrder;
import com.acme.care.legacy.pharmacy.PharmacyOrderService;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = "legacy.pharmacy.orders")
public class LegacyPharmacyListener {

    private final PharmacyOrderService orders;

    public LegacyPharmacyListener(PharmacyOrderService orders) {
        this.orders = orders;
    }

    @RabbitHandler
    public void onNewOrder(PharmacyOrder order) {
        orders.accept(order);
    }

    @RabbitHandler
    public void onCancellation(PharmacyCancellation cancellation) {
        orders.cancel(cancellation.orderId());
    }
}
