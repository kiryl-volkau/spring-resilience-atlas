package com.acme.care.crm.web;

import java.util.UUID;

import com.acme.care.crm.api.CrmLeadEndpoints;
import com.acme.care.crm.service.LeadService;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CrmLeadController implements CrmLeadEndpoints {

    private final LeadService leadService;

    public CrmLeadController(LeadService leadService) {
        this.leadService = leadService;
    }

    @Override
    public LeadDto getLead(UUID leadId) {
        return leadService.get(leadId);
    }

    @Override
    public LeadDto createLead(CreateLeadRequest request) {
        return leadService.create(request.name(), request.email(), request.source());
    }

    @Override
    public LeadDto changeStatus(UUID leadId, LeadStatusChange change) {
        return leadService.changeStatus(leadId, change.status(), change.note());
    }
}
