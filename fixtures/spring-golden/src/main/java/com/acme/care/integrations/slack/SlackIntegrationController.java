package com.acme.care.integrations.slack;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/slack")
public class SlackIntegrationController {

    private final SlackOAuthService oauth;

    public SlackIntegrationController(SlackOAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping("/install")
    public ResponseEntity<Void> startInstall() {
        return ResponseEntity.status(HttpStatus.FOUND).location(oauth.authorizationUri()).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> completeInstall(@RequestParam String code, @RequestParam String state) {
        oauth.exchangeCode(code, state);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/settings/integrations?slack=connected"))
                .build();
    }

    @DeleteMapping("/installation")
    public ResponseEntity<Void> uninstall() {
        oauth.revokeInstallation();
        return ResponseEntity.noContent().build();
    }
}
