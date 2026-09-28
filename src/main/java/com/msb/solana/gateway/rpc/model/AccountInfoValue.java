package com.msb.solana.gateway.rpc.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Account data returned by {@code getAccountInfo}.
 *
 * @param lamports   account native balance in lamports (0 when the account does not exist)
 * @param data       base64-encoded account data, or an empty list for data-less accounts
 * @param owner      base58 program owner (null when the account does not exist)
 * @param executable whether the account is a program account
 * @param space      number of bytes of account data
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountInfoValue(
        @JsonProperty("lamports") long lamports,
        @JsonProperty("data") List<String> data,
        @JsonProperty("owner") String owner,
        @JsonProperty("executable") boolean executable,
        @JsonProperty("space") long space) {
}
