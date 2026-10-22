package com.msb.solana.gateway.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.gateway.serialization.Ed25519SignatureVerifier;
import com.msb.solana.gateway.serialization.SolanaAddressValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency guarantees of {@link ChannelVoucherVerifier}.
 *
 * <p>Finding #3: the legacy implementation performed a non-atomic
 * {@code getOrDefault} / {@code put} check-then-act on an in-memory nonce map,
 * so N concurrent presentations of the same signed voucher could all observe the
 * same previous watermark and all be authorized. This test forces that interleaving
 * deterministically with a barrier inside cryptographic verification.
 */
class ChannelVoucherVerifierConcurrencyTest {

    private static final int CONCURRENCY = 32;
    private static final String CHANNEL_ID = "chan_concurrency_001";
    private static final String PAYER = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";
    private static final long AMOUNT = 5_000L;
    private static final long NONCE = 7L;

    private final Ed25519SignatureVerifier signatureVerifier = Mockito.mock(Ed25519SignatureVerifier.class);
    private final SolanaAddressValidator addressValidator = Mockito.mock(SolanaAddressValidator.class);
    private final EscrowBalanceProvider escrowBalanceProvider = Mockito.mock(EscrowBalanceProvider.class);
    private final PaymentAuditRepository auditRepository = Mockito.mock(PaymentAuditRepository.class);

    private ChannelVoucherVerifier newVerifier() {
        when(addressValidator.isValid(anyString())).thenReturn(true);
        when(escrowBalanceProvider.getVerifiedDepositCeiling(anyString())).thenReturn(1_000_000L);
        when(auditRepository.findTopByChannelIdOrderByNonceDesc(anyString())).thenReturn(Optional.empty());
        return new ChannelVoucherVerifier(
                signatureVerifier, addressValidator, escrowBalanceProvider, auditRepository);
    }

    private PaymentVoucher voucher(String channelId, long nonce) {
        return new PaymentVoucher(channelId, PAYER, AMOUNT, nonce, "signatureBase58");
    }

    private PaymentAuditRecord persistedRecord(String channelId, long nonce) {
        return PaymentAuditRecord.create(
                channelId, PAYER, AMOUNT, nonce, "signatureBase58", PaymentAuditStatus.VERIFIED);
    }

    @Test
    @DisplayName("Concurrent presentation of the same nonce authorizes exactly one request")
    void concurrentIdenticalNonce_authorizesExactlyOne() throws Exception {
        // Deterministically expose the check-then-act race: no thread may advance the
        // nonce watermark until every other thread has already read the previous
        // watermark and passed cryptographic verification.
        CountDownLatch allThreadsVerified = new CountDownLatch(CONCURRENCY);
        when(signatureVerifier.verifyBase58(any(), anyString(), anyString())).thenAnswer(invocation -> {
            allThreadsVerified.countDown();
            if (!allThreadsVerified.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency barrier timed out");
            }
            return true;
        });

        ChannelVoucherVerifier verifier = newVerifier();
        PaymentVoucher voucher = voucher(CHANNEL_ID, NONCE);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>(CONCURRENCY);
        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                outcomes.add(pool.submit(() -> {
                    startGate.await(10, TimeUnit.SECONDS);
                    return verifier.verifyVoucher(voucher, AMOUNT);
                }));
            }
            startGate.countDown();

            int authorized = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(20, TimeUnit.SECONDS)) {
                    authorized++;
                }
            }

            assertThat(authorized)
                    .as("exactly one of %d concurrent presentations of nonce %d may be authorized",
                            CONCURRENCY, NONCE)
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A replay after a restart is rejected using the persisted ledger watermark")
    void replayAfterRestart_isRejectedFromPersistedWatermark() {
        ChannelVoucherVerifier verifier = newVerifier();
        when(auditRepository.findTopByChannelIdOrderByNonceDesc(CHANNEL_ID))
                .thenReturn(Optional.of(persistedRecord(CHANNEL_ID, NONCE)));
        when(signatureVerifier.verifyBase58(any(), anyString(), anyString())).thenReturn(true);

        // Simulate a JVM restart: the in-memory watermark cache is empty, but the
        // nonce is already committed to the append-only ledger.
        verifier.resetState();

        assertThat(verifier.verifyVoucher(voucher(CHANNEL_ID, NONCE), AMOUNT))
                .as("a nonce already committed to the ledger must never be authorized again")
                .isFalse();
        assertThat(verifier.verifyVoucher(voucher(CHANNEL_ID, NONCE - 1), AMOUNT))
                .as("nonces below the persisted watermark are equally stale")
                .isFalse();
        assertThat(verifier.verifyVoucher(voucher(CHANNEL_ID, NONCE + 1), AMOUNT))
                .as("a strictly higher nonce remains valid after restart")
                .isTrue();
    }

    @Test
    @DisplayName("A forged high nonce with an invalid signature never advances the watermark")
    void invalidSignature_doesNotAdvanceWatermark() {
        ChannelVoucherVerifier verifier = newVerifier();
        // First presentation is forged (invalid signature), second is the legitimate
        // voucher for the same nonce.
        when(signatureVerifier.verifyBase58(any(), anyString(), anyString())).thenReturn(false, true);

        assertThat(verifier.verifyVoucher(voucher(CHANNEL_ID, NONCE), AMOUNT)).isFalse();
        assertThat(verifier.verifyVoucher(voucher(CHANNEL_ID, NONCE), AMOUNT))
                .as("an unauthenticated voucher must not be able to consume the nonce")
                .isTrue();
        assertThat(verifier.verifyVoucher(voucher(CHANNEL_ID, NONCE), AMOUNT))
                .as("once consumed, the nonce is closed")
                .isFalse();
    }

    @Test
    @DisplayName("An unverifiable escrow ceiling fails closed before any nonce claim")
    void depletedEscrow_failsClosedWithoutClaimingNonce() {
        ChannelVoucherVerifier verifier = newVerifier();
        when(escrowBalanceProvider.getVerifiedDepositCeiling(CHANNEL_ID)).thenReturn(0L);

        assertThat(verifier.verifyVoucher(voucher(CHANNEL_ID, NONCE), AMOUNT)).isFalse();

        verify(auditRepository, never()).findTopByChannelIdOrderByNonceDesc(CHANNEL_ID);
        verify(signatureVerifier, never()).verifyBase58(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("A voucher with a missing, blank, or oversized channel id fails closed")
    void missingOrOversizedChannelId_failsClosed() {
        ChannelVoucherVerifier verifier = newVerifier();

        assertThat(verifier.verifyVoucher(voucher(null, NONCE), AMOUNT)).isFalse();
        assertThat(verifier.verifyVoucher(voucher("   ", NONCE), AMOUNT)).isFalse();
        assertThat(verifier.verifyVoucher(voucher("c".repeat(65), NONCE), AMOUNT)).isFalse();

        verify(signatureVerifier, never()).verifyBase58(any(), anyString(), anyString());
    }
}
