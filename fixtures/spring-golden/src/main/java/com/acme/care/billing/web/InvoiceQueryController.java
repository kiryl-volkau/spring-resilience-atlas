package com.acme.care.billing.web;

import java.util.UUID;

import com.acme.care.billing.api.InvoicesApi;
import com.acme.care.billing.api.model.InvoiceDto;
import com.acme.care.billing.api.model.InvoicePageDto;
import com.acme.care.billing.api.model.VoidInvoiceRequestDto;
import com.acme.care.billing.service.InvoiceQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvoiceQueryController implements InvoicesApi {

    private final InvoiceQueryService queryService;

    public InvoiceQueryController(InvoiceQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public ResponseEntity<InvoicePageDto> listInvoices(String residentId, Integer page) {
        return ResponseEntity.ok(queryService.page(UUID.fromString(residentId), page == null ? 0 : page));
    }

    @Override
    public ResponseEntity<InvoiceDto> getInvoice(String invoiceId) {
        return ResponseEntity.of(queryService.find(UUID.fromString(invoiceId)));
    }

    @Override
    public ResponseEntity<InvoiceDto> voidInvoice(String invoiceId, VoidInvoiceRequestDto request) {
        return ResponseEntity.ok(queryService.voidInvoice(UUID.fromString(invoiceId), request.getReason()));
    }
}
