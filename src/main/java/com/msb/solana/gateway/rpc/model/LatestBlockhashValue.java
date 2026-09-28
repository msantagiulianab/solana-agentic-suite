package com.msb.solana.gateway.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result value of {@code getLatestBlockhash}.
 *
 * @param blockhash            base58-encoded recent blockhash to use in transactions
 * @param lastValidBlockHeight last slot height at which the blockhash remains valid
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LatestBlockhashValue(
        @JsonProperty("blockhash") String blockhash,
        @JsonProperty("lastValidBlockHeight") long lastValidBlockHeight) {
}
