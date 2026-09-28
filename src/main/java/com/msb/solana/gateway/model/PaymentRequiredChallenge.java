package com.msb.solana.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaymentRequiredChallenge(
    @JsonProperty("x402Version") int x402Version,
    @JsonProperty("scheme") String scheme,
    @JsonProperty("network") String network,
    @JsonProperty("escrowAddress") String escrowAddress,
    @JsonProperty("asset") String asset,
    @JsonProperty("priceAtomicUnits") long priceAtomicUnits,
    @JsonProperty("unit") String unit,
    @JsonProperty("message") String message
) {
    public static PaymentRequiredChallenge defaultSolanaChallenge(String escrowPubkey, long priceAtomicUnits) {
        return new PaymentRequiredChallenge(
            2,
            "channel",
            "solana:devnet",
            escrowPubkey,
            "USDC",
            priceAtomicUnits,
            "per-call",
            "Payment required via Solana payment channel or gasless voucher"
        );
    }
}