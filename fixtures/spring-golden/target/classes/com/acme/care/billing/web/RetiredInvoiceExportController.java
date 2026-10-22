package com.acme.care.billing.web;

import java.time.YearMonth;

import com.acme.care.billing.service.InvoiceExportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RetiredInvoiceExportController {

    private final InvoiceExportService exportService;

    public RetiredInvoiceExportController(InvoiceExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/api/billing/invoices/export")
    public ResponseEntity<byte[]> exportInvoices(@RequestParam YearMonth period) {
        return ResponseEntity.ok(exportService.csv(period));
    }
}
