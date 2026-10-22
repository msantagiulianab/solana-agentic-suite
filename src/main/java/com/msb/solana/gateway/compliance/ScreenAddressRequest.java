package com.msb.solana.gateway.compliance;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload for {@code POST /api/v1/compliance/screen-address}.
 *
 * <p>Validated at the HTTP boundary with Jakarta Bean Validation so a missing or
 * blank address is rejected with a sanitized {@code 400} before it reaches the
 * screening engine.
 */
public record ScreenAddressRequest(
        @JsonProperty("address")
        @NotBlank(message = "must not be blank")
        String address) {
}
