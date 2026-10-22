package com.acme.care.billing.client;

import java.math.BigDecimal;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "ledger", url = "${care.ledger.base-url}")
public interface LedgerClient {

    record LedgerEntry(String accountId, BigDecimal amount, String reference) {
    }

    @GetMapping("/v2/accounts/{accountId}/balance")
    BigDecimal getBalance(@PathVariable("accountId") String accountId);

    @PostMapping("/v2/entries")
    void postEntry(@RequestBody LedgerEntry entry);
}
