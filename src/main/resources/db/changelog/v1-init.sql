-- Safe Store (Audit Log)
CREATE TABLE safe_store_trade (
    id BIGSERIAL PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    portfolio_id UUID NOT NULL,
    trade_id UUID NOT NULL UNIQUE, -- Idempotency Guard
    symbol TEXT NOT NULL,
    side TEXT NOT NULL,
    price_per_stock DOUBLE PRECISION NOT NULL,
    quantity BIGINT NOT NULL,
    event_timestamp TIMESTAMP NOT NULL
);

-- Outbox Table
CREATE TABLE outbox_event (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    portfolio_id UUID NOT NULL,
    trade_id UUID NOT NULL,
    payload BYTEA NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT DEFAULT 0
);

-- Index specifically optimized for the Poller
CREATE INDEX idx_outbox_polling
ON outbox_event (created_at)
WHERE status = 'PENDING';

-- DLQ for poison messages
CREATE TABLE dlq_entry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    failed_at TIMESTAMPTZ DEFAULT NOW(),
    raw_message BYTEA,
    error_detail TEXT
);