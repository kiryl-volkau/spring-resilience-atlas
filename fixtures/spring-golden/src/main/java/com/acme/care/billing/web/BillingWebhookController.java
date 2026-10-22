package com.acme.care.billing.web;

import com.acme.care.billing.service.InvalidSignatureException;
import com.acme.care.billing.service.StripeWebhookService;
import com.acme.care.shared.api.ApiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.Billing.BASE)
public class BillingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BillingWebhookController.class);

    private final StripeWebhookService webhookService;

    public BillingWebhookController(StripeWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping(path = ApiPaths.Billing.WEBHOOK, consumes = "application/json")
    public ResponseEntity<Void> receiveStripeEvent(@RequestHeader("Stripe-Signature") String signature,
                                                   @RequestBody String payload) {
        try {
            webhookService.handle(signature, payload);
        } catch (InvalidSignatureException e) {
            log.warn("Rejected Stripe webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().build();
    }
}
