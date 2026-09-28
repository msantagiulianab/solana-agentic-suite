package com.msb.solana.rwa.entity;

/**
 * Off-chain settlement lifecycle of an on-chain mint attempt.
 *
 * <p>{@link #PENDING} is persisted <em>before</em> the Solana RPC broadcast so a
 * retried/failed dispatch can always be correlated to a durable off-chain row;
 * {@link #CONFIRMED} marks a successful mint (or a transaction whose signature
 * is visible on-chain at the confirmed commitment); {@link #FINALIZED} marks a
 * transaction that has reached the highest, irreversible Solana commitment;
 * {@link #FAILED} marks an attempt whose on-chain execution errored;
 * {@link #EXPIRED} marks an outbox record whose finality confirmation timed out
 * (fail-closed) without ever observing a finalized commitment.
 *
 * <p>This is an additive {@link jakarta.persistence.EnumType#STRING} enum backed
 * by unconstrained {@code varchar} columns, so appending values never requires a
 * schema change and never rewrites previously applied history.
 */
public enum SettlementStatus {
    PENDING,
    CONFIRMED,
    FINALIZED,
    FAILED,
    EXPIRED
}
