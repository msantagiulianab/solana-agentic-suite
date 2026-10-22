package com.msb.solana.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * x402 v2 payment requirement returned in the HTTP 402 challenge.
 *
 * <p>Instances are produced exclusively by
 * {@link com.msb.solana.gateway.service.X402ChallengeFactory} so the challenge and
 * the discovery document share one configuration source.
 */
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
}