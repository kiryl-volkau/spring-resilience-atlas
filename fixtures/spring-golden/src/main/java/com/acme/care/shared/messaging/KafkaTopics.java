package com.acme.care.shared.messaging;

public final class KafkaTopics {

    public static final String PREFIX = "care.";

    public static final String CONTACT_UPDATES = PREFIX + "crm.contact-updates";
    public static final String VITALS = PREFIX + "dailycare.vitals";
    public static final String PAYMENT_EVENTS = PREFIX + "billing.payment-events";
    public static final String DEVICE_TELEMETRY = PREFIX + "dailycare.device-telemetry";

    private KafkaTopics() {
    }
}
