package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parsed account data returned by {@code getAccountInfo}.
 *
 * @param owner      base58 program owner (null when the account does not exist)
 * @param lamports   account balance in lamports (0 when the account does not exist)
 * @param executable whether the account is a program account
 * @param space      number of bytes of account data
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountInfo(
        @JsonProperty("owner") String owner,
        @JsonProperty("lamports") long lamports,
        @JsonProperty("executable") boolean executable,
        @JsonProperty("space") long space) {

    /**
     * @return true when the wallet exists on-chain.
     */
    public boolean exists() {
        return owner != null;
    }
}
