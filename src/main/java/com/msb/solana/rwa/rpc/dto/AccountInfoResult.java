package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result payload of {@code getAccountInfo}.
 *
 * @param context RPC context (slot)
 * @param value   parsed account info, or null when the account does not exist
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountInfoResult(
        @JsonProperty("context") RpcContext context,
        @JsonProperty("value") AccountInfo value) {

    /**
     * @return the account info, or an absent sentinel when the account does not exist.
     */
    public AccountInfo valueOrAbsent() {
        return value != null ? value : new AccountInfo(null, 0L, false, 0L);
    }
}
