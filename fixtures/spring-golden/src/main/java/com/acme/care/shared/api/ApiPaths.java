package com.acme.care.shared.api;

public final class ApiPaths {

    public static final String API = "/api";

    public static final class Billing {
        public static final String BASE = API + "/billing";
        public static final String WEBHOOK = "/webhooks/stripe";

        private Billing() {
        }
    }

    public static final class Crm {
        public static final String BASE = API + "/crm";
        public static final String ADMIN = BASE + "/admin";

        private Crm() {
        }
    }

    private ApiPaths() {
    }
}
