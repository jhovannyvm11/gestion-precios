CREATE TABLE product_rules (
    id UUID PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    margin_percentage NUMERIC(7, 2) NOT NULL CHECK (margin_percentage >= 0 AND margin_percentage <= 1000),
    preferred_provider VARCHAR(20) NOT NULL CHECK (preferred_provider IN ('AUTO', 'PROVIDER_A', 'PROVIDER_B')),
    active BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);