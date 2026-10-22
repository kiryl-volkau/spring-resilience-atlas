package com.acme.care.crm.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Lead management contract, also consumed by the website enquiry form through a generated
 * TypeScript client.
 */
@RequestMapping("/api/crm/leads")
public interface CrmLeadEndpoints {

    record LeadDto(UUID leadId, String name, String email, String status) {
    }

    record CreateLeadRequest(@NotBlank String name, @NotBlank String email, String source) {
    }

    record LeadStatusChange(@NotBlank String status, String note) {
    }

    @GetMapping("/{leadId}")
    LeadDto getLead(@PathVariable("leadId") UUID leadId);

    @PostMapping
    LeadDto createLead(@RequestBody @Valid CreateLeadRequest request);

    @PatchMapping("/{leadId}/status")
    LeadDto changeStatus(@PathVariable("leadId") UUID leadId, @RequestBody @Valid LeadStatusChange change);
}
