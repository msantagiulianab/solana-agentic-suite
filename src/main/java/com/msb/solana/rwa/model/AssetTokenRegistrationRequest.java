package com.msb.solana.rwa.model;

import com.msb.solana.rwa.validation.ValidSolanaAddress;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Request payload to tokenize a new real-world asset.
 *
 * @param assetName           human-readable asset name
 * @param valuationUsd        USD valuation, must be greater than zero
 * @param issuerWalletAddress issuer's Solana wallet (base58, 32-44 chars)
 * @param idempotencyKey      client-supplied unique key guarding duplicate broadcasts
 */
@Getter
@Builder
public class AssetTokenRegistrationRequest {

    @NotBlank(message = "assetName must not be blank")
    private final String assetName;

    @NotNull(message = "valuationUsd must not be null")
    @DecimalMin(value = "0.01", message = "valuationUsd must be greater than 0")
    private final BigDecimal valuationUsd;

    @NotBlank(message = "issuerWalletAddress must not be blank")
    @ValidSolanaAddress(message = "issuerWalletAddress must be a valid Solana address")
    private final String issuerWalletAddress;

    @NotBlank(message = "idempotencyKey must not be blank")
    @Size(max = 255, message = "idempotencyKey must not exceed 255 characters")
    private final String idempotencyKey;
}