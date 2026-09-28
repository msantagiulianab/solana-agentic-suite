package com.msb.solana.gateway.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC context wrapper returned by Solana methods.
 *
 * @param slot slot number at which the node evaluated the query
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RpcContext(
        @JsonProperty("slot") long slot) {
}
