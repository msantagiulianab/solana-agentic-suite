-- ============================================================================
-- V2__add_settlement_tx_signature.sql
-- Extends the append-only payment audit ledger with the on-chain settlement
-- transaction signature recorded when a channel is swept to the treasury.
--
-- The column is nullable so previously-appended VERIFIED records (which have
-- not yet been swept) remain valid. It is populated exactly once by the
-- ChannelSettlementService when a record transitions VERIFIED -> SETTLED.
-- ============================================================================

ALTER TABLE payment_audit_ledger
    ADD COLUMN tx_signature varchar(88);
