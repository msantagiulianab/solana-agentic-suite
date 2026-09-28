package com.msb.solana.gateway.entity;

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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Immutable, append-only audit record for a successfully verified x402 payment
 * voucher.
 *
 * <p>The entity exposes no mutators and is created exclusively through
 * {@link #create(String, String, long, long, String, PaymentAuditStatus)}. The
 * unique database index on {@code (channel_id, nonce)} (see
 * {@code V1__init_payment_audit_ledger.sql}) enforces anti-replay at the
 * constraint level, so a replayed nonce can never be persisted.
 */
@Entity
@Table(
        name = "payment_audit_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payment_audit_ledger_channel_nonce",
                columnNames = {"channel_id", "nonce"}),
        indexes = {
                @Index(name = "idx_payment_audit_ledger_payer_pubkey", columnList = "payer_pubkey"),
                @Index(name = "idx_payment_audit_ledger_channel_id", columnList = "channel_id")
        })
public class PaymentAuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false, length = 64)
    private String channelId;

    @Column(name = "payer_pubkey", nullable = false, length = 44)
    private String payerPubkey;

    @Column(name = "cumulative_amount_atomic", nullable = false)
    private long cumulativeAmountAtomic;

    @Column(name = "nonce", nullable = false)
    private long nonce;

    @Column(name = "signature", nullable = false, length = 88)
    private String signature;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentAuditStatus status;

    @Column(name = "tx_signature", length = 88)
    private String txSignature;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA requires a no-arg constructor; never invoked by application code. */
    protected PaymentAuditRecord() {
    }

    private PaymentAuditRecord(String channelId, String payerPubkey, long cumulativeAmountAtomic,
                               long nonce, String signature, PaymentAuditStatus status) {
        this.channelId = channelId;
        this.payerPubkey = payerPubkey;
        this.cumulativeAmountAtomic = cumulativeAmountAtomic;
        this.nonce = nonce;
        this.signature = signature;
        this.status = status;
    }

    public static PaymentAuditRecord create(String channelId, String payerPubkey, long cumulativeAmountAtomic,
                                            long nonce, String signature, PaymentAuditStatus status) {
        return new PaymentAuditRecord(channelId, payerPubkey, cumulativeAmountAtomic, nonce, signature, status);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getPayerPubkey() {
        return payerPubkey;
    }

    public long getCumulativeAmountAtomic() {
        return cumulativeAmountAtomic;
    }

    public long getNonce() {
        return nonce;
    }

    public String getSignature() {
        return signature;
    }

    public PaymentAuditStatus getStatus() {
        return status;
    }

    public String getTxSignature() {
        return txSignature;
    }

    /**
     * Transitions a verified voucher record into the settled state after its
     * cumulative amount has been swept on-chain. The only legal transition is
     * {@code VERIFIED -> SETTLED}; all voucher fields remain immutable.
     */
    public void markSettled(String txSignature) {
        if (this.status != PaymentAuditStatus.VERIFIED) {
            throw new IllegalStateException("Only VERIFIED records can transition to SETTLED");
        }
        if (txSignature == null || txSignature.isBlank()) {
            throw new IllegalArgumentException("txSignature is required for settlement");
        }
        this.status = PaymentAuditStatus.SETTLED;
        this.txSignature = txSignature;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
