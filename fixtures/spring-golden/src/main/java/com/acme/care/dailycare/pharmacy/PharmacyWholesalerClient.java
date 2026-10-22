package com.acme.care.dailycare.pharmacy;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange(url = "/wholesale/v1", accept = "application/json")
public interface PharmacyWholesalerClient {

    record StockLevel(String sku, int available) {
    }

    record PurchaseOrder(String sku, int quantity, String homeCode) {
    }

    record OrderConfirmation(String orderId, String status) {
    }

    @GetExchange("/stock/{sku}")
    StockLevel stockLevel(@PathVariable String sku);

    @PostExchange("/orders")
    OrderConfirmation placeOrder(@RequestBody PurchaseOrder order);
}
