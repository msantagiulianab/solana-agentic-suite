-- ============================================================================
-- V2__create_rwa_tables.sql
-- RWA (Real-World Asset) compliance / attestation domain, ported from the
-- solana-rwa-enterprise-bridge backend. Backs the x402-protected
-- POST /api/v1/rwa/attest endpoint.
--
-- Mirrors the JPA entities Investor, AssetToken, and AuditLog exactly so that
-- production's hibernate.ddl-auto: validate passes and no column is missing.
-- The bridge's additive settlement columns (idempotency_key, settlement_status,
-- compute budget, transaction signature, slot, blockhash) are included because
-- the ported entities already model the full schema.
-- ============================================================================

CREATE TABLE investors (
    id              uuid                     NOT NULL,
    full_name       varchar(255)             NOT NULL,
    email           varchar(255)             NOT NULL,
    wallet_address  varchar(44)              NOT NULL,
    kyc_status      varchar(32)              NOT NULL,
    country         varchar(2),
    created_at      timestamp with time zone NOT NULL,
    updated_at      timestamp with time zone NOT NULL,
    CONSTRAINT pk_investors PRIMARY KEY (id),
    CONSTRAINT uk_investors_wallet_address UNIQUE (wallet_address)
);

CREATE INDEX idx_investors_wallet_address ON investors (wallet_address);

CREATE TABLE asset_tokens (
    id                uuid                     NOT NULL,
    asset_name        varchar(255)             NOT NULL,
    valuation_usd     numeric(20, 2)           NOT NULL,
    mint_address      varchar(44),
    compliance_status varchar(32)              NOT NULL,
    idempotency_key   varchar(255),
    settlement_status varchar(32),
    created_at        timestamp with time zone NOT NULL,
    updated_at        timestamp with time zone NOT NULL,
    CONSTRAINT pk_asset_tokens PRIMARY KEY (id),
    CONSTRAINT uk_asset_tokens_mint_address UNIQUE (mint_address)
);

CREATE INDEX idx_asset_tokens_mint_address ON asset_tokens (mint_address);
CREATE UNIQUE INDEX uk_asset_tokens_idempotency_key ON asset_tokens (idempotency_key);

CREATE TABLE audit_logs (
    id                                uuid                     NOT NULL,
    wallet_address                    varchar(44)              NOT NULL,
    action                            varchar(64)              NOT NULL,
    status                            varchar(16)              NOT NULL,
    reason                            text,
    timestamp                         timestamp with time zone NOT NULL,
    idempotency_key                   varchar(255),
    asset_id                          varchar(255),
    kyc_verified                      boolean,
    ofac_passed                       boolean,
    settlement_status                 varchar(32),
    compute_unit_price_micro_lamports bigint,
    compute_unit_limit                integer,
    solana_transaction_signature      varchar(88),
    slot                              bigint,
    blockhash                         varchar(88),
    CONSTRAINT pk_audit_logs PRIMARY KEY (id)
);

CREATE INDEX idx_audit_logs_wallet_address ON audit_logs (wallet_address);
CREATE UNIQUE INDEX uk_audit_logs_idempotency_key ON audit_logs (idempotency_key);
