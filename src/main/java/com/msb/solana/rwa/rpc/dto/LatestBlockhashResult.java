package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result payload of {@code getLatestBlockhash}.
 *
 * @param context RPC context (slot)
 * @param value   recent blockhash and last valid block height
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LatestBlockhashResult(
        @JsonProperty("context") RpcContext context,
        @JsonProperty("value") LatestBlockhash value) {
}