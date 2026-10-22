package com.acme.care.billing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CustomerReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(CustomerReconciliationService.class);

    private final JdbcTemplate jdbcTemplate;

    public CustomerReconciliationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void reconcileCustomer(String providerAccountId, String providerEventId) {
        int updated = jdbcTemplate.update(
                "update billing_customer set last_provider_event_id = ?, reconciled_at = now() where provider_account_id = ?",
                providerEventId, providerAccountId);
        if (updated == 0) {
            log.warn("No billing customer for provider account {}", providerAccountId);
        }
    }
}
