package com.msb.solana.gateway.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Standard JSON-RPC 2.0 response envelope.
 *
 * @param jsonrpc protocol version (always {@code "2.0"})
 * @param result  method-specific result payload (null when an error occurred)
 * @param error   JSON-RPC error object (null on success)
 * @param id      request id echoed back by the node
 * @param <T>     concrete result type
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RpcResponse<T>(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("result") T result,
        @JsonProperty("error") RpcError error,
        @JsonProperty("id") long id) {

    /**
     * @return true when the node returned a JSON-RPC error object.
     */
    public boolean hasError() {
        return error != null;
    }
}
