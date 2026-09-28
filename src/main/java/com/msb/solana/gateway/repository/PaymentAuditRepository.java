package com.msb.solana.gateway.repository;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence access for the immutable {@link PaymentAuditRecord} append-only
 * audit ledger. No update/delete operations are declared; records are only ever
 * inserted and read.
 */
public interface PaymentAuditRepository extends JpaRepository<PaymentAuditRecord, Long> {

    List<PaymentAuditRecord> findByChannelId(String channelId);

    List<PaymentAuditRecord> findByPayerPubkey(String payerPubkey);

    Optional<PaymentAuditRecord> findFirstByChannelIdAndNonce(String channelId, long nonce);

    Optional<PaymentAuditRecord> findTopByChannelIdOrderByNonceDesc(String channelId);

    boolean existsByChannelIdAndNonce(String channelId, long nonce);
}
