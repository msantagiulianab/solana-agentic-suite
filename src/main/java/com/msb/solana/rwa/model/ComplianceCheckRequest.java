package com.msb.solana.rwa.model;

import com.msb.solana.rwa.validation.ValidSolanaAddress;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Getter;

/**
 * Request payload for a compliance eligibility check.
 *
 * @param walletAddress    investor's Solana wallet (base58, 32-44 chars)
 * @param assetMintAddress Solana mint address of the asset token
 */
@Getter
@Builder
public class ComplianceCheckRequest {

    @NotBlank(message = "walletAddress must not be blank")
    @ValidSolanaAddress
    private final String walletAddress;

    @NotBlank(message = "assetMintAddress must not be blank")
    private final String assetMintAddress;
}
