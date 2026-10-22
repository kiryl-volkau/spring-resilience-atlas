package com.acme.care.billing.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    List<Invoice> findByProviderCustomerIdOrderByIssueDateDesc(String providerCustomerId);
}
