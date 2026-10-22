package com.acme.care.billing.client;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class PaymentProviderClient {

    public record ChargeResult(String chargeId, String status) {
    }

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public PaymentProviderClient(@Qualifier("paymentProviderRestTemplate") RestTemplate restTemplate,
                                 @Value("${care.billing.payment-provider.base-url}") String baseUrl,
                                 @Value("${care.billing.payment-provider.api-key}") String apiKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public ChargeResult createCharge(String customerId, long amountMinor, String currency) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        Map<String, Object> body = Map.of(
                "customer", customerId,
                "amount", amountMinor,
                "currency", currency);
        return restTemplate.postForObject(baseUrl + "/v1/charges", new HttpEntity<>(body, headers), ChargeResult.class);
    }
}
