package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token balance returned by {@code getTokenAccountBalance}.
 *
 * @param amount         raw integer token amount as a string (base units)
 * @param decimals       number of base-10 digits after the decimal point
 * @param uiAmountString human-readable amount including decimals
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenAccountBalance(
        @JsonProperty("amount") String amount,
        @JsonProperty("decimals") int decimals,
        @JsonProperty("uiAmountString") String uiAmountString) {
}
