CREATE TABLE billing_ledger_posting (
    id           BIGSERIAL PRIMARY KEY,
    invoice_id   UUID         NOT NULL,
    amount_minor BIGINT       NOT NULL,
    kind         VARCHAR(64)  NOT NULL,
    synced_at    TIMESTAMPTZ,
    viewed_at    TIMESTAMPTZ
);

ALTER TABLE billing_invoice RENAME COLUMN status TO invoice_status;
