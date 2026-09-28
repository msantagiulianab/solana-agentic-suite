package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * JSON-RPC 2.0 request payload for the Solana {@code simulateTransaction}
 * method.
 *
 * <p>Carries the base64-encoded wire transaction plus a fail-safe simulation
 * config: signature verification is disabled (rehearsal only), the transaction
 * is supplied as base64, and the recent blockhash is replaced so a dry run can
 * be evaluated without a valid recent blockhash on the encoded transaction.
 *
 * @param jsonRpc protocol version ("2.0")
 * @param id      request id echoed back by the node
 * @param method  JSON-RPC method name ("simulateTransaction")
 * @param params  positional params: [base64 wire transaction, simulation config]
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimulationRequestDto(
        @JsonProperty("jsonrpc") String jsonRpc,
        @JsonProperty("id") Long id,
        @JsonProperty("method") String method,
        @JsonProperty("params") List<Object> params) {

    /**
     * Builds a standard {@code simulateTransaction} request with the
     * institutional fail-safe simulation configuration.
     *
     * @param encodedTransaction base64-encoded Solana wire transaction
     * @param id                 JSON-RPC request id
     */
    public static SimulationRequestDto of(String encodedTransaction, long id) {
        return new SimulationRequestDto(
                "2.0",
                id,
                "simulateTransaction",
                List.of(encodedTransaction, SimulationConfig.defaults()));
    }

    /**
     * {@code simulateTransaction} configuration options.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimulationConfig(
            @JsonProperty("sigVerify") boolean sigVerify,
            @JsonProperty("encoding") String encoding,
            @JsonProperty("replaceRecentBlockhash") boolean replaceRecentBlockhash) {

        /**
         * @return fail-safe defaults: skip signature verification, accept the
         *         transaction as base64, and replace the recent blockhash.
         */
        public static SimulationConfig defaults() {
            return new SimulationConfig(false, "base64", true);
        }
    }
}
