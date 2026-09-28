-- ============================================================================
-- V1__init_payment_audit_ledger.sql
-- Append-only audit ledger for the x402 payment channel gateway.
--
-- Every successfully verified payment voucher is appended here exactly once.
-- The unique index on (channel_id, nonce) enforces anti-replay at the database
-- constraint level: a replayed nonce for the same channel can never be persisted
-- even if the in-memory verifier were bypassed.
--
-- This table is append-only by contract: the application never issues UPDATE or
-- DELETE against it, and the JPA entity exposes no mutators.
-- ============================================================================

CREATE TABLE payment_audit_ledger (
    id                       bigserial                NOT NULL,
    channel_id               varchar(64)              NOT NULL,
    payer_pubkey             varchar(44)              NOT NULL,
    cumulative_amount_atomic bigint                   NOT NULL,
    nonce                    bigint                   NOT NULL,
    signature                varchar(88)              NOT NULL,
    status                   varchar(32)              NOT NULL,
    created_at               timestamp with time zone NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_payment_audit_ledger PRIMARY KEY (id)
);

-- Anti-replay at the database constraint level.
CREATE UNIQUE INDEX uk_payment_audit_ledger_channel_nonce
    ON payment_audit_ledger (channel_id, nonce);

CREATE INDEX idx_payment_audit_ledger_payer_pubkey
    ON payment_audit_ledger (payer_pubkey);

CREATE INDEX idx_payment_audit_ledger_channel_id
    ON payment_audit_ledger (channel_id);
