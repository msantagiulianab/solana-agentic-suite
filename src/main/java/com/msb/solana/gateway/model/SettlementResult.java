package com.msb.solana.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of an on-chain channel settlement sweep.
 *
 * @param channelId          x402 payment channel identifier
 * @param settledAmountAtomic cumulative amount swept to the treasury in atomic units
 * @param txSignature        base58 Solana transaction signature returned by the node
 * @param timestamp          epoch millis at which the sweep completed
 */
public record SettlementResult(
        @JsonProperty("channelId") String channelId,
        @JsonProperty("settledAmountAtomic") long settledAmountAtomic,
        @JsonProperty("txSignature") String txSignature,
        @JsonProperty("timestamp") long timestamp) {
}
