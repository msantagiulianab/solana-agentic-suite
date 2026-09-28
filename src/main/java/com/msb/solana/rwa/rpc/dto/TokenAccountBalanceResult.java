package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result payload of {@code getTokenAccountBalance}.
 *
 * @param context RPC context (slot)
 * @param value   parsed token balance, or null when the token account does not exist
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenAccountBalanceResult(
        @JsonProperty("context") RpcContext context,
        @JsonProperty("value") TokenAccountBalance value) {
}
