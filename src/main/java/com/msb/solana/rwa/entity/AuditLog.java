package com.msb.solana.rwa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit trail of every transaction attempt (approved or blocked),
 * in accordance with the enterprise compliance rules.
 *
 * <p>In addition to the compliance action/status, the ledger captures the
 * settlement-proof metadata consumed by the compliance export engine: the
 * client idempotency key (unique, to prevent duplicate RPC broadcasts), asset
 * id, KYC/OFAC flags, execution status, compute budget, transaction signature,
 * slot, and blockhash. Legacy rows created before the settlement phase leave
 * these settlement columns {@code null}.
 */
@Entity
@Table(name = "audit_logs",
        indexes = @Index(name = "idx_audit_logs_wallet_address", columnList = "wallet_address"))
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "wallet_address", nullable = false, length = 44)
    private String walletAddress;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AuditLogStatus status;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "asset_id", length = 255)
    private String assetId;

    @Column(name = "kyc_verified")
    private Boolean kycVerified;

    @Column(name = "ofac_passed")
    private Boolean ofacPassed;

    @Column(name = "settlement_status", length = 32)
    private String settlementStatus;

    @Column(name = "compute_unit_price_micro_lamports")
    private Long computeUnitPriceMicroLamports;

    @Column(name = "compute_unit_limit")
    private Integer computeUnitLimit;

    @Column(name = "solana_transaction_signature", length = 88)
    private String solanaTransactionSignature;

    @Column(name = "slot")
    private Long slot;

    @Column(name = "blockhash", length = 88)
    private String blockhash;

    @Builder
    public AuditLog(String walletAddress, String action, AuditLogStatus status, String reason, Instant timestamp,
                    String idempotencyKey, String assetId, Boolean kycVerified, Boolean ofacPassed,
                    String settlementStatus, Long computeUnitPriceMicroLamports, Integer computeUnitLimit,
                    String solanaTransactionSignature, Long slot, String blockhash) {
        this.walletAddress = walletAddress;
        this.action = action;
        this.status = status;
        this.reason = reason;
        this.timestamp = timestamp;
        this.idempotencyKey = idempotencyKey;
        this.assetId = assetId;
        this.kycVerified = kycVerified;
        this.ofacPassed = ofacPassed;
        this.settlementStatus = settlementStatus;
        this.computeUnitPriceMicroLamports = computeUnitPriceMicroLamports;
        this.computeUnitLimit = computeUnitLimit;
        this.solanaTransactionSignature = solanaTransactionSignature;
        this.slot = slot;
        this.blockhash = blockhash;
    }

    @PrePersist
    void onCreate() {
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
    }
}
