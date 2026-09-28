package com.msb.solana.gateway.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result payload of {@code getLatestBlockhash}.
 *
 * @param context RPC context (slot)
 * @param value   recent blockhash and last valid block height
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LatestBlockhashResponse(
        @JsonProperty("context") RpcContext context,
        @JsonProperty("value") LatestBlockhashValue value) {
}
