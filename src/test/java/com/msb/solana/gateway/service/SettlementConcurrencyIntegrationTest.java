package com.msb.solana.gateway.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.gateway.rpc.SolanaRpcClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding #6: the settlement sweep had no idempotency guard, so two concurrent
 * sweeps of the same channel both read {@code VERIFIED}, both broadcast an
 * on-chain transfer, and only then raced on the status update. The second
 * broadcast is unrecoverable (double-spend), so the guard must claim the record
 * <em>before</em> the RPC call.
 *
 * <p>This test drives two real service invocations against the real H2
 * repository (row lock + conditional state transition) and a mocked RPC
 * boundary, and asserts that exactly one transaction is ever broadcast.
 */
@SpringBootTest
@ActiveProfiles("test")
class SettlementConcurrencyIntegrationTest {

    private static final String PAYER = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";
    private static final String BLOCKHASH = "11111111111111111111111111111111";

    @Autowired
    private ChannelSettlementService settlementService;

    @Autowired
    private PaymentAuditRepository auditRepository;

    @MockitoBean
    private SolanaRpcClient rpcClient;

    private String channelId;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        channelId = "settle_race_" + System.nanoTime();
        auditRepository.save(PaymentAuditRecord.create(
                channelId, PAYER, 5_000L, 1L, "voucherSig", PaymentAuditStatus.VERIFIED));
        when(rpcClient.getLatestBlockhash()).thenReturn(Optional.of(BLOCKHASH));
    }

    @AfterEach
    void tearDown() {
        auditRepository.deleteAll();
    }

    @Test
    @DisplayName("Two concurrent sweeps of the same channel broadcast exactly one transaction")
    void concurrentSweeps_broadcastExactlyOnce() throws Exception {
        AtomicInteger broadcasts = new AtomicInteger();
        when(rpcClient.sendTransaction(anyString())).thenAnswer(invocation -> {
            broadcasts.incrementAndGet();
            // Hold the winning transaction open so the loser definitely reaches the
            // guard while the record is still being settled.
            Thread.sleep(150L);
            return Optional.of("txSigMock");
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>(2);
        try {
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    startGate.await(10, TimeUnit.SECONDS);
                    try {
                        settlementService.settleChannel(channelId);
                        return true;
                    } catch (IllegalStateException alreadySettledOrClaimed) {
                        return false;
                    }
                }));
            }
            startGate.countDown();

            int successes = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes)
                    .as("exactly one concurrent sweep may win the settlement claim")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(broadcasts.get())
                .as("the losing sweep must never reach sendTransaction")
                .isEqualTo(1);
        verify(rpcClient, times(1)).sendTransaction(anyString());
        assertThat(auditRepository.findByChannelId(channelId).get(0).getStatus())
                .isEqualTo(PaymentAuditStatus.SETTLED);
    }
}
