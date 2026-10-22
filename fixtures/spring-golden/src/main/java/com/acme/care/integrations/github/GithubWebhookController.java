package com.acme.care.integrations.github;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/github")
public class GithubWebhookController {

    private final GithubSignatureVerifier signatureVerifier;
    private final CarePolicyRepositorySync policySync;

    public GithubWebhookController(GithubSignatureVerifier signatureVerifier, CarePolicyRepositorySync policySync) {
        this.signatureVerifier = signatureVerifier;
        this.policySync = policySync;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(@RequestHeader("X-Hub-Signature-256") String signature,
                                        @RequestHeader("X-GitHub-Event") String eventType,
                                        @RequestBody String payload) {
        if (!signatureVerifier.isValidSignature(payload, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        policySync.onRepositoryEvent(eventType, payload);
        return ResponseEntity.accepted().build();
    }
}
