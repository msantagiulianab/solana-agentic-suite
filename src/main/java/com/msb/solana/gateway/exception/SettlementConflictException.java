package com.msb.solana.gateway.exception;

/**
 * Raised when a settlement sweep cannot obtain (or loses) the exclusive claim on
 * a verified ledger record, for example because a concurrent sweep is already
 * broadcasting the same channel.
 *
 * <p>Extends {@link IllegalStateException} so existing {@code 409 Conflict}
 * mappings keep their behaviour while callers can catch the precise type.
 */
public class SettlementConflictException extends IllegalStateException {

    public SettlementConflictException(String message) {
        super(message);
    }
}
