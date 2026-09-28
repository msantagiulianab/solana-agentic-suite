package com.msb.solana.gateway.compliance;

/**
 * Final decision produced by the compliance screening engine for a single
 * Solana address.
 */
public enum ScreeningVerdict {
    BLOCKED,
    CLEAR_TO_TRANSACT
}
