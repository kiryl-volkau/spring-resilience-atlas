package com.acme.care.integrations.webhooks;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/webhooks")
public class WebhookSubscriptionController {

    public record EndpointView(String endpointId, String url, List<String> eventTypes, boolean active) {
    }

    public record SecretView(String endpointId, String secret) {
    }

    private final OutboundEndpointRegistry registry;

    public WebhookSubscriptionController(OutboundEndpointRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<EndpointView> listEndpoints() {
        return registry.all();
    }

    @PostMapping("/secret")
    public SecretView rotateSecret(@RequestParam String endpointId) {
        return registry.rotateSecret(endpointId);
    }
}
