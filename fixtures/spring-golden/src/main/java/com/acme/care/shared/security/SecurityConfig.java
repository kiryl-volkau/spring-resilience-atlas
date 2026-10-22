package com.acme.care.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/integrations/**", "/api/public/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/integrations/github/webhook", "/api/public/**").permitAll()
                        .requestMatchers("/api/billing/webhooks/**").permitAll()
                        .requestMatchers("/api/billing/admin/**").hasRole("BILLING_ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
