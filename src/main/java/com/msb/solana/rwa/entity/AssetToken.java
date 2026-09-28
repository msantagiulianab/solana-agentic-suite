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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Real-world asset token registered off-chain. The Solana mint address is
 * populated only after a successful on-chain mint and is unique when set.
 *
 * <p>The client-supplied {@code idempotencyKey} is unique and is persisted
 * together with a {@link SettlementStatus} <em>before</em> the mint RPC is
 * dispatched, so retries can never create a duplicate broadcast and every
 * on-chain mint has a durable off-chain record.
 */
@Entity
@Table(name = "asset_tokens",
        indexes = @Index(name = "idx_asset_tokens_mint_address", columnList = "mint_address"))
@Getter
@Setter
@NoArgsConstructor
public class AssetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "asset_name", nullable = false, length = 255)
    private String assetName;

    @Column(name = "valuation_usd", nullable = false, precision = 20, scale = 2)
    private BigDecimal valuationUsd;

    @Column(name = "mint_address", unique = true, length = 44)
    private String mintAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_status", nullable = false, length = 32)
    private AssetTokenComplianceStatus complianceStatus;

    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status", length = 32)
    private SettlementStatus settlementStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    public AssetToken(String assetName, BigDecimal valuationUsd, String mintAddress,
                      AssetTokenComplianceStatus complianceStatus,
                      String idempotencyKey, SettlementStatus settlementStatus) {
        this.assetName = assetName;
        this.valuationUsd = valuationUsd;
        this.mintAddress = mintAddress;
        this.complianceStatus = complianceStatus;
        this.idempotencyKey = idempotencyKey;
        this.settlementStatus = settlementStatus;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
