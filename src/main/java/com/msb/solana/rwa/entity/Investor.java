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

import java.time.Instant;
import java.util.UUID;

/**
 * Off-chain investor record. The wallet address is unique and indexed; the
 * KYC/AML status gates any Solana RPC dispatch.
 */
@Entity
@Table(name = "investors",
        indexes = @Index(name = "idx_investors_wallet_address", columnList = "wallet_address"))
@Getter
@Setter
@NoArgsConstructor
public class Investor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "wallet_address", nullable = false, unique = true, length = 44)
    private String walletAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 32)
    private KycStatus kycStatus;

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    public Investor(String fullName, String email, String walletAddress, KycStatus kycStatus, String country) {
        this.fullName = fullName;
        this.email = email;
        this.walletAddress = walletAddress;
        this.kycStatus = kycStatus;
        this.country = country;
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