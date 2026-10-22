package com.msb.solana.gateway.exception;

/**
 * Raised when the append-only payment ledger rejects a voucher because its
 * {@code (channel_id, nonce)} pair already exists.
 *
 * <p>This is the durable, cross-instance anti-replay guarantee: the unique
 * constraint on the ledger is the final authority, even if two application
 * instances both believed the nonce was fresh in memory. The payment filter maps
 * this exception to {@code 403 Forbidden}, never to a {@code 400} or a leaked
 * {@code 500}.
 */
public class DuplicateVoucherNonceException extends RuntimeException {

    public DuplicateVoucherNonceException(String message) {
        super(message);
    }

    public DuplicateVoucherNonceException(String message, Throwable cause) {
        super(message, cause);
    }
}
