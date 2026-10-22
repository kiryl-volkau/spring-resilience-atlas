package com.acme.care.dailycare.supplies;

import java.util.List;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class CareSupplyController implements CareSupplyExchange {

    private final SupplyInventory inventory;

    public CareSupplyController(SupplyInventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public List<SupplyItem> listItems() {
        return inventory.all();
    }

    @Override
    public SupplyItem addItem(SupplyItem item) {
        return inventory.add(item);
    }
}
