package com.msb.solana.rwa.service;

import com.msb.solana.rwa.model.AssetTokenRegistrationRequest;
import com.msb.solana.rwa.entity.AssetToken;
import com.msb.solana.rwa.entity.AssetTokenComplianceStatus;
import com.msb.solana.rwa.entity.Investor;
import com.msb.solana.rwa.entity.KycStatus;
import com.msb.solana.rwa.entity.SettlementStatus;
import com.msb.solana.rwa.exception.AssetTokenNotFoundException;
import com.msb.solana.rwa.rpc.SolanaRpcException;
import com.msb.solana.rwa.repository.AssetTokenRepository;
import com.msb.solana.rwa.repository.InvestorRepository;
import com.msb.solana.rwa.rpc.SolanaRpcAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TokenService {

    private final AssetTokenRepository assetTokenRepository;
    private final SolanaMintService solanaMintService;
    private final InvestorRepository investorRepository;
    private final SolanaRpcAdapter solanaRpcAdapter;

    public List<AssetToken> findAll() {
        return assetTokenRepository.findAll();
    }

    public AssetToken findById(UUID id) {
        return assetTokenRepository.findById(id)
                .orElseThrow(() -> new AssetTokenNotFoundException(id.toString(), true));
    }

    /**
     * Tokenizes a real-world asset after passing the pre-flight compliance gate.
     *
     * <p>The issuer wallet is verified <em>before</em> any on-chain mint occurs:
     * the issuer must be registered, KYC {@link KycStatus#VERIFIED}, and present
     * on-chain. Any failure aborts immediately with {@code 422} so that
     * {@link SolanaMintService#createMint()} is never invoked (fail-closed).
     *
     * <p>The client-supplied idempotency key is validated first and then used to
     * guarantee exactly-once broadcast semantics: a replayed key returns the
     * already-settled asset without dispatching a second mint, while a new key
     * is persisted in {@link SettlementStatus#PENDING} state <em>before</em> the
     * RPC dispatch so a failure can never leave an on-chain mint without a
     * durable off-chain record (no orphan mints).
     */
    @Transactional
    public AssetToken create(AssetTokenRegistrationRequest request) {
        String idempotencyKey = validateIdempotencyKey(request.getIdempotencyKey());
        String issuerWalletAddress = request.getIssuerWalletAddress();
        assertIssuerCompliant(issuerWalletAddress);

        Optional<AssetToken> existing = assetTokenRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Persist a PENDING settlement record BEFORE any RPC bytes are emitted.
        // On retry, the unique idempotency key short-circuits above so a second
        // broadcast is never dispatched for the same logical request.
        AssetToken token = assetTokenRepository.save(AssetToken.builder()
                .assetName(request.getAssetName())
                .valuationUsd(request.getValuationUsd())
                .idempotencyKey(idempotencyKey)
                .complianceStatus(AssetTokenComplianceStatus.COMPLIANT)
                .settlementStatus(SettlementStatus.PENDING)
                .build());

        try {
            String mintAddress = solanaMintService.createMint();
            token.setMintAddress(mintAddress);
            token.setSettlementStatus(SettlementStatus.CONFIRMED);
            return assetTokenRepository.save(token);
        } catch (RuntimeException ex) {
            token.setSettlementStatus(SettlementStatus.FAILED);
            assetTokenRepository.save(token);
            throw ex;
        }
    }

    private String validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency key is required");
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency key must not exceed 255 characters");
        }
        return normalized;
    }

    /**
     * Fail-closed pre-flight KYC/AML + on-chain existence check for the issuer.
     *
     * @throws ResponseStatusException (422) when the issuer is not registered,
     *         KYC-blocked, not verified, absent on-chain, or when the RPC layer
     *         is unavailable.
     */
    private void assertIssuerCompliant(String walletAddress) {
        Investor investor = investorRepository.findByWalletAddress(walletAddress).orElse(null);

        if (investor == null) {
            throw blocked("Investor not registered");
        }
        KycStatus kycStatus = investor.getKycStatus();
        if (kycStatus == KycStatus.REJECTED) {
            throw blocked("Investor KYC status is REJECTED");
        }
        if (kycStatus == KycStatus.FLAGGED_SANCTION) {
            throw blocked("Investor is flagged for sanctions screening");
        }
        if (kycStatus != KycStatus.VERIFIED) {
            throw blocked("Investor KYC verification is not complete (status: " + kycStatus + ")");
        }

        try {
            if (!solanaRpcAdapter.getAccountInfo(walletAddress).exists()) {
                throw blocked("Wallet does not exist on Solana chain");
            }
        } catch (SolanaRpcException ex) {
            throw blocked("Solana RPC unavailable - on-chain verification failed");
        }
    }

    private ResponseStatusException blocked(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Tokenization blocked by compliance: " + reason);
    }
}