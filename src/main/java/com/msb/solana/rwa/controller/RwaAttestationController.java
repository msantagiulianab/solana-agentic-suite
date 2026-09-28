package com.msb.solana.rwa.controller;

import com.msb.solana.rwa.model.ComplianceCheckRequest;
import com.msb.solana.rwa.model.ComplianceCheckResponse;
import com.msb.solana.rwa.service.ComplianceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * x402-protected RWA attestation endpoint.
 *
 * <p>The {@code X402PaymentFilter} guards every {@code /api/v1/*} route, so an
 * unauthenticated call to {@code POST /api/v1/rwa/attest} is challenged with
 * {@code HTTP 402 Payment Required} before this controller is ever reached. A
 * caller presenting a valid signed {@code PAYMENT-SIGNATURE} voucher reaches the
 * off-chain compliance gatekeeper below.
 */
@RestController
@RequestMapping("/api/v1/rwa")
public class RwaAttestationController {

    private final ComplianceService complianceService;

    public RwaAttestationController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @PostMapping("/attest")
    public ResponseEntity<ComplianceCheckResponse> attest(
            @Valid @RequestBody ComplianceCheckRequest request) {
        ComplianceCheckResponse response = complianceService.verifyEligibility(
                request.getWalletAddress(), request.getAssetMintAddress());
        return ResponseEntity.ok(response);
    }
}
