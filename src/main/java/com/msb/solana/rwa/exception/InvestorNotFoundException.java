package com.msb.solana.rwa.exception;

import java.util.UUID;

/**
 * Thrown when an operation requires a registered investor, but no investor
 * record exists for the requested wallet address or UUID.
 */
public class InvestorNotFoundException extends RuntimeException {

    public InvestorNotFoundException(String walletAddress) {
        super("Investor not registered for wallet address: " + walletAddress);
    }

    public InvestorNotFoundException(UUID id) {
        super("Investor not found for id: " + id);
    }
}
