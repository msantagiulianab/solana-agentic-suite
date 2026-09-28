package com.msb.solana.rwa.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Result payload of {@code getSignatureStatuses}.
 *
 * @param context RPC context (slot)
 * @param value   per-signature statuses, index-aligned with the requested
 *                signatures (null when the node returned no values)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SignatureStatusesResult(
        @JsonProperty("context") RpcContext context,
        @JsonProperty("value") List<SignatureStatus> value) {
}
