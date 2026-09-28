package com.msb.solana.gateway.compliance;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload for {@code POST /api/v1/compliance/screen-address}.
 */
public record ScreenAddressRequest(
        @JsonProperty("address") String address) {
}
