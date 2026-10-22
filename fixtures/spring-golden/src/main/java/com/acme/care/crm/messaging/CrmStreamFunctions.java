package com.acme.care.crm.messaging;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.acme.care.crm.service.ContactService;
import com.acme.care.crm.service.ContactService.ContactUpsert;
import com.acme.care.crm.service.EnrichmentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

@Configuration
public class CrmStreamFunctions {

    public record ContactEnrichmentRequest(String contactId, String email) {
    }

    public record LeadCaptured(String leadId, String name, String email, String source) {
    }

    public record CrmHeartbeat(String instance, Instant at) {
    }

    @Bean
    public Consumer<Message<ContactEnrichmentRequest>> enrichContact(EnrichmentService enrichmentService) {
        return message -> enrichmentService.enrich(message.getPayload().contactId(), message.getPayload().email());
    }

    @Bean
    public Function<LeadCaptured, ContactUpsert> leadToContact(ContactService contactService) {
        return lead -> contactService.upsertFromLead(lead.leadId(), lead.name(), lead.email(), lead.source());
    }

    @Bean
    public Supplier<CrmHeartbeat> crmHeartbeat() {
        return () -> new CrmHeartbeat(System.getenv().getOrDefault("HOSTNAME", "local"), Instant.now());
    }
}
