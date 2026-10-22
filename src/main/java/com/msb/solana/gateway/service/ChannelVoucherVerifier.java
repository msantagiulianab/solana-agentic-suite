package com.msb.solana.gateway.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.gateway.serialization.Ed25519SignatureVerifier;
import com.msb.solana.gateway.serialization.SolanaAddressValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, concurrency-safe verifier for x402 channel vouchers.
 *
 * <p>Anti-replay is enforced with a monotonically increasing per-channel nonce
 * watermark. The watermark is advanced with a lock-free compare-and-set, so
 * exactly one of N concurrent presentations of the same nonce can ever win the
 * claim; the losers observe the advanced watermark and fail closed.
 *
 * <p>On first use of a channel the watermark is seeded from the highest nonce
 * already committed to the append-only {@code payment_audit_ledger}. That keeps
 * replay protection effective across JVM restarts and makes the in-memory
 * decision consistent with the durable ledger. The database unique constraint on
 * {@code (channel_id, nonce)} remains the final authority: even if two
 * application instances race on the same nonce, only one insert can commit and
 * the loser is rejected with HTTP 403 before the paid resource is served.
 */
@Service
public class ChannelVoucherVerifier {
    private static final Logger log = LoggerFactory.getLogger(ChannelVoucherVerifier.class);

    private final Ed25519SignatureVerifier signatureVerifier;
    private final SolanaAddressValidator addressValidator;
    private final EscrowBalanceProvider escrowBalanceProvider;
    private final PaymentAuditRepository auditRepository;

    private final ConcurrentMap<String, AtomicLong> nonceWatermarks = new ConcurrentHashMap<>();

    public ChannelVoucherVerifier(Ed25519SignatureVerifier signatureVerifier,
                                  SolanaAddressValidator addressValidator,
                                  EscrowBalanceProvider escrowBalanceProvider,
                                  PaymentAuditRepository auditRepository) {
        this.signatureVerifier = signatureVerifier;
        this.addressValidator = addressValidator;
        this.escrowBalanceProvider = escrowBalanceProvider;
        this.auditRepository = auditRepository;
    }

    public boolean verifyVoucher(PaymentVoucher voucher, long requiredAmount) {
        if (voucher == null) {
            log.warn("Voucher rejection: null voucher payload");
            return false;
        }

        // The channel id is both the anti-replay partition key and the ledger column
        // (varchar(64)). Reject absent or oversized ids before any canonical byte
        // construction so a malformed payload can never surface as a 500.
        if (voucher.channelId() == null || voucher.channelId().isBlank() || voucher.channelId().length() > 64) {
            log.warn("Voucher rejection: missing or oversized channel id");
            return false;
        }

        if (!addressValidator.isValid(voucher.payerPubkey())) {
            log.warn("Voucher rejection: Invalid payer Base58 public key: {}", voucher.payerPubkey());
            return false;
        }

        long verifiedDepositCeiling = escrowBalanceProvider.getVerifiedDepositCeiling(voucher.channelId());
        if (voucher.cumulativeAmountAtomic() > verifiedDepositCeiling) {
            log.warn("Voucher rejection: Cumulative spend {} exceeds verified escrow deposit ceiling {} for channel {}",
                    voucher.cumulativeAmountAtomic(), verifiedDepositCeiling, voucher.channelId());
            return false;
        }

        byte[] canonicalBytes = voucher.getCanonicalPayload();
        if (!signatureVerifier.verifyBase58(canonicalBytes, voucher.signature(), voucher.payerPubkey())) {
            log.warn("Voucher rejection: Cryptographic signature mismatch for channel {}", voucher.channelId());
            return false;
        }

        // Only a fully authenticated voucher may advance the watermark. Claiming the
        // nonce before signature verification would let a forged, unsigned high nonce
        // poison the channel and lock out the legitimate payer.
        AtomicLong watermark = watermarkFor(voucher.channelId());
        long previousNonce = watermark.get();
        if (!claimNonce(watermark, voucher.nonce())) {
            log.warn("Voucher replay detected for channel {}: incoming nonce {} <= last seen {}",
                    voucher.channelId(), voucher.nonce(), watermark.get());
            return false;
        }
        if (log.isDebugEnabled()) {
            log.debug("Voucher accepted for channel {}: nonce advanced from {} to {}",
                    voucher.channelId(), previousNonce, voucher.nonce());
        }
        return true;
    }

    /**
     * Returns the channel watermark, seeding it from the durable ledger on first
     * use. The seed read happens outside the map lock so a cold-start stampede
     * cannot serialize unrelated channels behind a database round trip.
     */
    private AtomicLong watermarkFor(String channelId) {
        AtomicLong existing = nonceWatermarks.get(channelId);
        if (existing != null) {
            return existing;
        }

        long persistedNonce = auditRepository.findTopByChannelIdOrderByNonceDesc(channelId)
                .map(PaymentAuditRecord::getNonce)
                .orElse(0L);
        AtomicLong candidate = new AtomicLong(persistedNonce);
        AtomicLong raced = nonceWatermarks.putIfAbsent(channelId, candidate);
        return raced != null ? raced : candidate;
    }

    /**
     * Atomically advances {@code watermark} to {@code nonce} if and only if the
     * watermark is strictly lower. Returns {@code true} for the single winner.
     */
    private boolean claimNonce(AtomicLong watermark, long nonce) {
        while (true) {
            long current = watermark.get();
            if (nonce <= current) {
                return false;
            }
            if (watermark.compareAndSet(current, nonce)) {
                return true;
            }
        }
    }

    /**
     * Clears the in-memory watermark cache. The next voucher for a channel is
     * re-seeded from the durable ledger, so this is safe in production (it cannot
     * re-open a replay window) and keeps MockMvc tests isolated.
     */
    public void resetState() {
        nonceWatermarks.clear();
    }
}