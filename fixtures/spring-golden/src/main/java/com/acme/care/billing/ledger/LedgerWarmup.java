package com.acme.care.billing.ledger;

import com.acme.care.billing.client.LedgerClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class LedgerWarmup implements ApplicationRunner {

    private final LedgerClient ledgerClient;

    public LedgerWarmup(LedgerClient ledgerClient) {
        this.ledgerClient = ledgerClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        ledgerClient.getBalance("house");
    }
}
