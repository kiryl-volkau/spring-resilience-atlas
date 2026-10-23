CREATE TABLE billing_event_receipt (
    id                BIGSERIAL PRIMARY KEY,
    provider_event_id VARCHAR(255) NOT NULL,
    event_type        VARCHAR(255) NOT NULL,
    payload           JSONB        NOT NULL,
    received_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX billing_event_receipt_provider_event_id_idx ON billing_event_receipt (provider_event_id);

CREATE TABLE billing_paypal_receipt (
    id              BIGSERIAL PRIMARY KEY,
    paypal_event_id VARCHAR(255) NOT NULL,
    payload         TEXT         NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT billing_paypal_receipt_event_uk UNIQUE (paypal_event_id)
);

CREATE TABLE billing_invoice (
    id                   UUID PRIMARY KEY,
    provider_customer_id VARCHAR(255) NOT NULL,
    amount_minor         BIGINT       NOT NULL,
    currency             CHAR(3)      NOT NULL,
    issue_date           DATE         NOT NULL,
    status               VARCHAR(16),
    provider_charge_id   VARCHAR(255)
);
