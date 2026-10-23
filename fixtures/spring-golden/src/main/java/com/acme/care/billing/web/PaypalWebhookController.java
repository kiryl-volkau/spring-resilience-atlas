package com.acme.care.billing.web;

import com.acme.care.billing.service.InvalidSignatureException;
import com.acme.care.billing.service.PaypalWebhookService;
import com.acme.care.shared.api.ApiPaths;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.Billing.BASE)
public class PaypalWebhookController {

    private final PaypalWebhookService webhookService;

    public PaypalWebhookController(PaypalWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping(path = "/webhooks/paypal", consumes = "application/json")
    public ResponseEntity<Void> receivePaypalEvent(@RequestHeader("PAYPAL-TRANSMISSION-SIG") String signature,
                                                   @RequestBody String payload) {
        try {
            webhookService.handle(signature, payload);
        } catch (InvalidSignatureException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().build();
    }
}
