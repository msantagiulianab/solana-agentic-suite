package com.msb.solana.gateway.compliance;

/**
 * Raised when a submitted Solana address is not a valid Base58-encoded
 * 32-byte public key. Translated to {@code 400 Bad Request} by
 * {@link ComplianceExceptionHandler}.
 */
public class InvalidSolanaAddressException extends RuntimeException {

    public InvalidSolanaAddressException(String message) {
        super(message);
    }
}
