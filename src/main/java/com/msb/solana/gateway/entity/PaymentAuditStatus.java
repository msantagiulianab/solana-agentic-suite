package com.msb.solana.gateway.entity;

/**
 * Outcome persisted into the append-only payment audit ledger.
 * Stored as a varchar-backed enum column ({@code @Enumerated(EnumType.STRING)}).
 *
 * <p>Lifecycle: {@code VERIFIED} &rarr; {@code SETTLING} &rarr; {@code SETTLED}.
 * The intermediate {@code SETTLING} state is written by an atomic conditional
 * update <em>before</em> the on-chain sweep is broadcast, so a concurrent sweep
 * can never double-broadcast. An ambiguous broadcast failure lands in the
 * terminal {@code SETTLEMENT_FAILED} state instead of reverting to
 * {@code VERIFIED}, so a retry can never double-spend without manual
 * reconciliation. Voucher fields themselves are never mutated.
 */
public enum PaymentAuditStatus {
    VERIFIED,
    SETTLING,
    SETTLEMENT_FAILED,
    SETTLED
}
