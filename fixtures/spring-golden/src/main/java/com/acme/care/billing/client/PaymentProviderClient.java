package com.acme.care.billing.client;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class PaymentProviderClient {

    public record ChargeResult(String chargeId, String status) {
    }

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public PaymentProviderClient(@Qualifier("paymentProviderRestTemplate") RestTemplate restTemplate,
                                 @Value("${care.billing.payment-provider.base-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public ChargeResult createCharge(String customerId, long amountMinor, String currency) {
        Map<String, Object> body = Map.of(
                "customer", customerId,
                "amount", amountMinor,
                "currency", currency);
        return restTemplate.postForObject(baseUrl + "/v1/charges", body, ChargeResult.class);
    }
}
