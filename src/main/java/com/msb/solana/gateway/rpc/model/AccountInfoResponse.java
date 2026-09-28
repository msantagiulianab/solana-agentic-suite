package com.msb.solana.gateway.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result payload of {@code getAccountInfo}.
 *
 * @param context RPC context (slot)
 * @param value   parsed account info, or null when the account does not exist
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountInfoResponse(
        @JsonProperty("context") RpcContext context,
        @JsonProperty("value") AccountInfoValue value) {
}
