package com.acme.care.billing.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class BillingInfrastructureConfig {

    @Bean(name = "billingExecutor")
    public ExecutorService billingExecutor() {
        return Executors.newFixedThreadPool(4);
    }

    @Bean
    public RestTemplate paymentProviderRestTemplate() {
        return new RestTemplate();
    }
}
