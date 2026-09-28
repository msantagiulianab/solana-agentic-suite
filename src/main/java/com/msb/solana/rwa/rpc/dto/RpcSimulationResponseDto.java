package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.msb.solana.rwa.rpc.dto.RpcContext;

import java.util.List;

/**
 * Result payload of a {@code simulateTransaction} JSON-RPC response.
 *
 * <p>Modelled after the {@code context}/{@code value} shape used by other
 * Solana RPC results so it can be bound into the generic
 * {@code RpcEnvelope}. The {@link SimulationValue} holds the raw simulation
 * outcome; {@link #toSimulationResult()} maps it into the domain-facing
 * {@link SimulationResultDto}.
 *
 * @param context RPC context (slot)
 * @param value   simulation outcome (null when the node returned no value)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RpcSimulationResponseDto(
        @JsonProperty("context") RpcContext context,
        @JsonProperty("value") SimulationValue value) {

    /**
     * @return true when the node produced a value and reported no execution error.
     */
    public boolean isSuccessful() {
        return value != null && !value.hasError();
    }

    /**
     * Maps the raw RPC simulation outcome into the domain model.
     *
     * <p>A null/absent {@code err} is treated as a successful rehearsal. A
     * structured error node (for example an {@code InstructionError} with a
     * custom program code) is rendered into a stable diagnostic message.
     */
    public SimulationResultDto toSimulationResult() {
        if (value == null) {
            return new SimulationResultDto(false, null, List.of(),
                    "simulateTransaction returned an empty result", null);
        }
        if (!value.hasError()) {
            return new SimulationResultDto(true, value.unitsConsumed(), value.logs(), null, null);
        }
        return new SimulationResultDto(false, value.unitsConsumed(), value.logs(),
                describeError(value.err()), null);
    }

    private static String describeError(JsonNode err) {
        if (err == null || err.isNull()) {
            return "unknown simulation error";
        }
        return err.isTextual() ? err.asText() : err.toString();
    }

    /**
     * Raw simulation outcome reported by the node.
     *
     * @param err           execution error ({@code null}/{@code null} node on success)
     * @param logs          program execution logs
     * @param unitsConsumed compute units consumed by the simulated transaction
     * @param accounts      optional post-simulation account states
     * @param returnData    optional program return data
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimulationValue(
            @JsonProperty("err") JsonNode err,
            @JsonProperty("logs") List<String> logs,
            @JsonProperty("unitsConsumed") Long unitsConsumed,
            @JsonProperty("accounts") JsonNode accounts,
            @JsonProperty("returnData") JsonNode returnData) {

        /**
         * @return true when the node reported a non-null execution error.
         */
        public boolean hasError() {
            return err != null && !err.isNull();
        }
    }
}
