package com.acme.care.billing.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventReceiptRepository extends JpaRepository<EventReceipt, Long> {

    Optional<EventReceipt> findByProviderEventId(String providerEventId);
}
