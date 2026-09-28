package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Standard JSON-RPC 2.0 response envelope.
 *
 * @param jsonRpc protocol version ("2.0")
 * @param result  method-specific result payload (null when an error occurred)
 * @param error   JSON-RPC error object (null on success)
 * @param id      request id echoed back by the node
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RpcEnvelope<T>(
        @JsonProperty("jsonrpc") String jsonRpc,
        T result,
        RpcError error,
        Long id) {

    /**
     * @return true when the node returned a JSON-RPC error object.
     */
    public boolean hasError() {
        return error != null;
    }

    /**
     * @return true when the response is malformed (missing both result and error).
     */
    public boolean isMalformed() {
        return result == null && error == null;
    }
}
