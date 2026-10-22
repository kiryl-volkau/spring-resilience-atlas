package com.acme.care.billing.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.acme.care.billing.service.BillingAdminService.Tariff;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class TariffCache {

    private final BillingAdminService adminService;
    private final AtomicReference<List<Tariff>> tariffs = new AtomicReference<>(List.of());

    public TariffCache(BillingAdminService adminService) {
        this.adminService = adminService;
    }

    @PostConstruct
    void load() {
        tariffs.set(adminService.currentTariffs());
    }

    public List<Tariff> current() {
        return tariffs.get();
    }
}
