package com.acme.care.billing.web;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@TestComponent
@RestController
public class FakeLedgerController {

    @GetMapping("/v2/accounts/{accountId}/balance")
    public BigDecimal balance(@PathVariable String accountId) {
        return BigDecimal.ZERO;
    }

    @PostMapping("/v2/entries")
    public Map<String, String> postEntry(@RequestBody Map<String, Object> entry) {
        return Map.of("status", "booked");
    }
}
