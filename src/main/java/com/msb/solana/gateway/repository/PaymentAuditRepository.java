package com.msb.solana.gateway.repository;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistence access for the immutable {@link PaymentAuditRecord} append-only
 * audit ledger.
 *
 * <p>Voucher rows are only ever inserted and read. The only permitted UPDATEs are
 * the guarded settlement lifecycle transitions below, which change {@code status}
 * and {@code tx_signature} while leaving every signed voucher field untouched.
 * The conditional {@code WHERE status = :expected} clause is what makes the
 * settlement claim atomic: at most one concurrent sweep can observe 1 updated
 * row, so at most one on-chain broadcast can occur.
 */
public interface PaymentAuditRepository extends JpaRepository<PaymentAuditRecord, Long> {

    List<PaymentAuditRecord> findByChannelId(String channelId);

    List<PaymentAuditRecord> findByPayerPubkey(String payerPubkey);

    Optional<PaymentAuditRecord> findFirstByChannelIdAndNonce(String channelId, long nonce);

    Optional<PaymentAuditRecord> findTopByChannelIdOrderByNonceDesc(String channelId);

    boolean existsByChannelIdAndNonce(String channelId, long nonce);

    List<PaymentAuditRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Atomically claims a record for an in-flight settlement sweep by moving
     * {@code expected -> claimed} only while it is still in the expected state.
     *
     * @return {@code 1} for the single winning claim; {@code 0} when another
     *         sweep already claimed or completed the record
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentAuditRecord r
               set r.status = :claimed
             where r.id = :id
               and r.status = :expected
            """)
    int claimForSettlement(@Param("id") Long id,
                           @Param("expected") PaymentAuditStatus expected,
                           @Param("claimed") PaymentAuditStatus claimed);

    /**
     * Completes a claimed sweep and persists the broadcast transaction signature.
     *
     * @return {@code 1} when the claim was still held; {@code 0} otherwise
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentAuditRecord r
               set r.status = :settled,
                   r.txSignature = :txSignature
             where r.id = :id
               and r.status = :expected
            """)
    int completeSettlement(@Param("id") Long id,
                           @Param("expected") PaymentAuditStatus expected,
                           @Param("settled") PaymentAuditStatus settled,
                           @Param("txSignature") String txSignature);

    /**
     * Marks a claimed (in-flight) sweep as failed after an ambiguous broadcast
     * outcome. This is a terminal state: it deliberately does <em>not</em> return
     * the record to {@code VERIFIED}, because the on-chain transaction may already
     * have landed and a retry must never re-broadcast it.
     *
     * @return {@code 1} when the claim was still held; {@code 0} otherwise
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentAuditRecord r
               set r.status = :failed
             where r.id = :id
               and r.status = :expected
            """)
    int markSettlementFailed(@Param("id") Long id,
                             @Param("expected") PaymentAuditStatus expected,
                             @Param("failed") PaymentAuditStatus failed);

    /**
     * Releases a claim back to {@code VERIFIED} when the sweep could not start
     * (e.g. no recent blockhash could be fetched). Safe because no broadcast was
     * attempted, so the channel remains retryable without risk of double-spend.
     *
     * @return {@code 1} when the claim was still held; {@code 0} otherwise
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PaymentAuditRecord r
               set r.status = :released
             where r.id = :id
               and r.status = :expected
            """)
    int releaseSettlementClaim(@Param("id") Long id,
                               @Param("expected") PaymentAuditStatus expected,
                               @Param("released") PaymentAuditStatus released);
}
