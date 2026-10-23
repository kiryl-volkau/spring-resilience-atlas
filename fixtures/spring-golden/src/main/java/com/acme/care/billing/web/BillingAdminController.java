package com.acme.care.billing.web;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.acme.care.billing.service.BillingAdminService;
import com.acme.care.billing.service.BillingAdminService.InvoiceSummary;
import com.acme.care.billing.service.BillingAdminService.Tariff;
import com.acme.care.billing.service.ChargeRetryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing/admin/")
public class BillingAdminController {

    private final BillingAdminService adminService;
    private final ChargeRetryService chargeRetryService;

    public BillingAdminController(BillingAdminService adminService, ChargeRetryService chargeRetryService) {
        this.adminService = adminService;
        this.chargeRetryService = chargeRetryService;
    }

    @PostMapping(path = "/invoices/{invoiceId}/charge", params = "action=retry")
    public ResponseEntity<Void> retryCharge(@PathVariable UUID invoiceId) {
        chargeRetryService.retry(invoiceId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping(path = "/invoices/{invoiceId}/charge", params = "action=cancel")
    public ResponseEntity<Void> cancelCharge(@PathVariable UUID invoiceId) {
        chargeRetryService.cancel(invoiceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("invoices/")
    public List<InvoiceSummary> listInvoices(@RequestParam YearMonth period) {
        return adminService.invoicesFor(period);
    }

    @PostMapping("/invoices/{invoiceId}/reissue")
    public ResponseEntity<Void> reissueInvoice(@PathVariable UUID invoiceId) {
        adminService.reissue(invoiceId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping(path = {"/tariffs", "/tariffs/current"})
    public List<Tariff> tariffs() {
        return adminService.currentTariffs();
    }

    @RequestMapping(value = "/recalculate", method = {RequestMethod.POST, RequestMethod.GET}, params = "period")
    public ResponseEntity<Integer> recalculateBalances(@RequestParam YearMonth period) {
        return ResponseEntity.ok(adminService.recalculateBalances(period));
    }

    @GetMapping(path = "/statements/{statementId}", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadStatement(@PathVariable UUID statementId) {
        return ResponseEntity.ok(adminService.renderStatementPdf(statementId));
    }
}
