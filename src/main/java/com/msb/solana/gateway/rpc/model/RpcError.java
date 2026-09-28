package com.msb.solana.gateway.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 error object.
 *
 * @param code    numeric error code (e.g. {@code -32602} invalid params)
 * @param message human-readable error description
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RpcError(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message) {
}
