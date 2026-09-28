package com.msb.solana.gateway.entity;

/**
 * Outcome persisted into the append-only payment audit ledger.
 * Stored as a varchar-backed enum column ({@code @Enumerated(EnumType.STRING)}).
 */
public enum PaymentAuditStatus {
    VERIFIED,
    SETTLED
}
