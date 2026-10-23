package com.acme.care.billing.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaypalReceiptRepository extends JpaRepository<PaypalReceipt, Long> {

    @Query("select r from PaypalReceipt r where r.reference = :reference")
    Optional<PaypalReceipt> lookupByReference(@Param("reference") String reference);
}
