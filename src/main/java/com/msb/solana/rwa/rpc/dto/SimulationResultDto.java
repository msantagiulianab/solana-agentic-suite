package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Domain-facing result of a pre-flight transaction simulation.
 *
 * @param success                     true when the simulated transaction executed cleanly
 * @param unitsConsumed               compute units consumed by the simulation
 * @param logs                        program execution logs produced by the simulation
 * @param errorMessage                diagnostic description of a reverted simulation
 *                                    (null on success)
 * @param recommendedComputeUnitLimit recommended compute unit limit including the
 *                                    safety margin (null when not computed)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimulationResultDto(
        @JsonProperty("success") boolean success,
        @JsonProperty("unitsConsumed") Long unitsConsumed,
        @JsonProperty("logs") List<String> logs,
        @JsonProperty("errorMessage") String errorMessage,
        @JsonProperty("recommendedComputeUnitLimit") Long recommendedComputeUnitLimit) {
}
