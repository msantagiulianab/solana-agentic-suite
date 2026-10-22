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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies C1: settlement never holds a database transaction open across an
 * external Solana RPC call, and an ambiguous broadcast outcome lands in a
 * terminal non-VERIFIED state instead of reverting to {@code VERIFIED}.
 */
@SpringBootTest
@ActiveProfiles("test")
class SettlementTransactionBoundaryIntegrationTest {

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
        channelId = "settle_tx_boundary_" + System.nanoTime();
        auditRepository.save(PaymentAuditRecord.create(
                channelId, PAYER, 5_000L, 1L, "voucherSig", PaymentAuditStatus.VERIFIED));
    }

    @AfterEach
    void tearDown() {
        auditRepository.deleteAll();
    }

    @Test
    @DisplayName("sendTransaction runs with no active database transaction")
    void sendTransaction_executesOutsideDatabaseTransaction() {
        AtomicBoolean txActiveDuringSend = new AtomicBoolean(true);
        when(rpcClient.getLatestBlockhash()).thenReturn(Optional.of(BLOCKHASH));
        when(rpcClient.sendTransaction(anyString())).thenAnswer(invocation -> {
            txActiveDuringSend.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of("txSigBoundary");
        });

        settlementService.settleChannel(channelId);

        assertThat(txActiveDuringSend.get())
                .as("no DB transaction may be held open across sendTransaction")
                .isFalse();
        assertThat(auditRepository.findByChannelId(channelId).get(0).getStatus())
                .isEqualTo(PaymentAuditStatus.SETTLED);
    }

    @Test
    @DisplayName("an ambiguous broadcast failure leaves the record in SETTLEMENT_FAILED")
    void ambiguousBroadcastFailure_marksSettlementFailed() {
        when(rpcClient.getLatestBlockhash()).thenReturn(Optional.of(BLOCKHASH));
        when(rpcClient.sendTransaction(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.settleChannel(channelId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sendTransaction");

        assertThat(auditRepository.findByChannelId(channelId).get(0).getStatus())
                .as("an ambiguous broadcast must never revert to VERIFIED")
                .isEqualTo(PaymentAuditStatus.SETTLEMENT_FAILED);
    }

    @Test
    @DisplayName("a pre-broadcast blockhash failure releases the claim back to VERIFIED")
    void blockhashUnavailable_releasesClaim() {
        when(rpcClient.getLatestBlockhash()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.settleChannel(channelId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blockhash");

        assertThat(auditRepository.findByChannelId(channelId).get(0).getStatus())
                .as("nothing was broadcast, so the channel stays retryable")
                .isEqualTo(PaymentAuditStatus.VERIFIED);
        verify(rpcClient, never()).sendTransaction(anyString());
    }
}
