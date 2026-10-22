package com.acme.care.dailycare.supplies;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange("/api/exchange")
public interface CareSupplyExchange {

    record SupplyItem(String sku, String description, int quantity) {
    }

    @GetExchange("/items")
    List<SupplyItem> listItems();

    @PostExchange(url = "/items")
    SupplyItem addItem(@RequestBody SupplyItem item);
}
