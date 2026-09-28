package com.msb.solana.gateway.rpc.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Standard JSON-RPC 2.0 request envelope sent to a Solana RPC node.
 *
 * @param jsonrpc protocol version (always {@code "2.0"})
 * @param id      monotonically increasing request id echoed back by the node
 * @param method  RPC method name (e.g. {@code getAccountInfo})
 * @param params  method-specific positional parameters
 */
public record RpcRequest(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") long id,
        @JsonProperty("method") String method,
        @JsonProperty("params") Object params) {

    public static RpcRequest of(long id, String method, Object params) {
        return new RpcRequest("2.0", id, method, params);
    }
}
