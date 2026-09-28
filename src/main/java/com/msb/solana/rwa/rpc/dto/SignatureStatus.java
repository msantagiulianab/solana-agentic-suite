package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw per-signature status element returned by {@code getSignatureStatuses}.
 *
 * @param slot               slot number at which the transaction was observed (null when unknown)
 * @param confirmations      number of blocks confirming the transaction; null once finalized
 * @param confirmationStatus commitment at which the transaction was observed:
 *                           {@code "processed"}, {@code "confirmed"}, or {@code "finalized"}
 *                           (null when the node has not yet seen the transaction)
 * @param err                transaction error payload (null on success); may be a JSON object
 *                           ({@code {"InstructionError":[...]}}) or a plain string depending on the node
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SignatureStatus(
        @JsonProperty("slot") Long slot,
        @JsonProperty("confirmations") Long confirmations,
        @JsonProperty("confirmationStatus") String confirmationStatus,
        @JsonProperty("err") Object err) {
}
