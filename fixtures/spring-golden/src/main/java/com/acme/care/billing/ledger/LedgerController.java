package com.acme.care.billing.ledger;

import java.util.List;
import java.util.UUID;

import com.acme.care.shared.api.ApiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.Billing.BASE + "/ledger")
public class LedgerController {

    private static final Logger log = LoggerFactory.getLogger(LedgerController.class);

    private final LedgerStatementService statementService;
    private final LedgerSyncService syncService;
    private final BankTransferService bankTransferService;

    public LedgerController(LedgerStatementService statementService, LedgerSyncService syncService,
                            BankTransferService bankTransferService) {
        this.statementService = statementService;
        this.syncService = syncService;
        this.bankTransferService = bankTransferService;
    }

    @PostMapping("/{invoiceId}/statement")
    public List<LedgerPosting> issueStatement(@PathVariable UUID invoiceId) {
        return statementService.issueStatement(invoiceId);
    }

    @PostMapping("/{invoiceId}/adjustments")
    public void adjust(@PathVariable UUID invoiceId, @RequestParam long amountMinor) {
        syncService.adjust(invoiceId, amountMinor);
    }

    @PostMapping("/webhooks/bank-transfer")
    public ResponseEntity<Void> receiveBankTransfer(@RequestBody String payload) {
        try {
            bankTransferService.recordTransfer(payload);
        } catch (Exception e) {
            log.error("Bank transfer notification could not be recorded", e);
        }
        return ResponseEntity.ok().build();
    }
}
