package com.acme.care.billing.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerPostingRepository extends JpaRepository<LedgerPosting, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LedgerPosting p where p.invoiceId = :invoiceId")
    Optional<LedgerPosting> lockByInvoiceId(@Param("invoiceId") UUID invoiceId);

    List<LedgerPosting> findByInvoiceId(UUID invoiceId);

    @Query("select p from LedgerPosting p where p.syncedAt is null")
    List<LedgerPosting> findUnsynced();
}
