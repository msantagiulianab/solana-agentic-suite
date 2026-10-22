package com.msb.solana.gateway.service;

import com.msb.solana.gateway.model.PaymentRequiredChallenge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the x402 payment requirements advertised by the
 * gateway.
 *
 * <p>Both the normative {@code PAYMENT-REQUIRED} challenge emitted by
 * {@code X402PaymentFilter} and the machine-readable
 * {@code /.well-known/x402.json} discovery document are built here, so the
 * {@code x402.*} configuration can never drift between the two surfaces (or
 * fall back to two different escrow addresses).
 */
@Component
public class X402ChallengeFactory {

    static final int X402_VERSION = 2;
    static final String SCHEME_CHANNEL = "channel";
    static final String CHALLENGE_MESSAGE =
            "Payment required via Solana payment channel or gasless voucher";

    private final String escrowPubkey;
    private final String network;
    private final String asset;
    private final long priceAtomicUnits;
    private final String unit;

    public X402ChallengeFactory(
            @Value("${x402.escrow-pubkey:7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU}") String escrowPubkey,
            @Value("${x402.network:solana:devnet}") String network,
            @Value("${x402.asset:USDC}") String asset,
            @Value("${x402.price-atomic-units:5000}") long priceAtomicUnits,
            @Value("${x402.unit:per-call}") String unit) {
        this.escrowPubkey = escrowPubkey;
        this.network = network;
        this.asset = asset;
        this.priceAtomicUnits = priceAtomicUnits;
        this.unit = unit;
    }

    /**
     * @return the current x402 v2 payment requirement for this gateway
     */
    public PaymentRequiredChallenge createChallenge() {
        return new PaymentRequiredChallenge(
                X402_VERSION,
                SCHEME_CHANNEL,
                network,
                escrowPubkey,
                asset,
                priceAtomicUnits,
                unit,
                CHALLENGE_MESSAGE);
    }

    public long priceAtomicUnits() {
        return priceAtomicUnits;
    }
}
