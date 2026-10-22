package com.acme.care.billing.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@TestComponent
@RestController
public class StubPaymentProviderController {

    @PostMapping("/v1/charges")
    public Map<String, String> createCharge(@RequestBody Map<String, Object> body) {
        return Map.of("chargeId", "ch_" + UUID.randomUUID(), "status", "succeeded");
    }
}
