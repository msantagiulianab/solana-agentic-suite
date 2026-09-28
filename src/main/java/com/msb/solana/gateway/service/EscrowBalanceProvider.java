package com.msb.solana.gateway.service;

/**
 * Resolves the verified on-chain escrow deposit ceiling for a payment channel.
 *
 * <p>Implementations must fail closed: a missing, depleted, or unreadable escrow
 * account must yield a ceiling of {@code 0}, never a positive balance.
 */
public interface EscrowBalanceProvider {

    /**
     * @param channelId the x402 payment channel identifier
     * @return the verified escrow balance ceiling in atomic units, or {@code 0}
     *         when the escrow cannot be verified or holds no balance
     */
    long getVerifiedDepositCeiling(String channelId);
}
