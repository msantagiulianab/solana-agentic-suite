package com.msb.solana.rwa.model;

import com.msb.solana.rwa.entity.AssetTokenComplianceStatus;
import com.msb.solana.rwa.entity.KycStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Result of an off-chain compliance eligibility check.
 *
 * @param allowed        true when the investor is KYC-verified and the asset is compliant
 * @param reason         human-readable reason for the decision
 * @param investorStatus KYC status of the investor (null when unregistered)
 * @param assetStatus    compliance status of the asset (null when unregistered)
 * @param timestamp      instant at which the check was evaluated
 */
@Getter
@Builder
public class ComplianceCheckResponse {

    private final boolean allowed;
    private final String reason;
    private final KycStatus investorStatus;
    private final AssetTokenComplianceStatus assetStatus;
    private final Instant timestamp;
}
