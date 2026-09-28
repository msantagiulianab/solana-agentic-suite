package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single prioritization-fee sample returned by {@code getRecentPrioritizationFees}.
 *
 * @param slot              slot at which the fee was observed
 * @param prioritizationFee prioritization fee in micro-lamports
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrioritizationFee(
        @JsonProperty("slot") long slot,
        @JsonProperty("prioritizationFee") long prioritizationFee) {
}
