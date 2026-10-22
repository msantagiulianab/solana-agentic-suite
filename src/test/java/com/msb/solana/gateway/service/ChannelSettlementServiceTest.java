package com.msb.solana.gateway.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import com.msb.solana.gateway.exception.SettlementConflictException;
import com.msb.solana.gateway.model.SettlementResult;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.gateway.rpc.SolanaRpcClient;
import com.msb.solana.gateway.serialization.SolanaInstruction;
import com.msb.solana.gateway.serialization.SolanaKeypair;
import com.msb.solana.gateway.serialization.SolanaKeypairService;
import com.msb.solana.gateway.serialization.SolanaWireTransactionBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelSettlementServiceTest {

    private static final String TREASURY = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";
    private static final String MOCK_PRIVATE_KEY = "4wBqpZM9xaSheZzJSMawUKKwhdpChKbZ5eu5ky4Vigw";
    private static final String BLOCKHASH = "11111111111111111111111111111111";

    /**
     * No-op transaction manager so the service's {@code TransactionTemplate} runs
     * its callbacks inline without a real JPA transaction in this offline unit test.
     */
    private static final PlatformTransactionManager NOOP_TRANSACTION_MANAGER = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };

    @Mock
    private PaymentAuditRepository auditRepository;

    @Mock
    private SolanaRpcClient rpcClient;

    @Mock
    private SolanaWireTransactionBuilder transactionBuilder;

    @Mock
    private SolanaKeypairService keypairService;

    private final SolanaKeypairService realKeypairService = new SolanaKeypairService();

    private ChannelSettlementService service;

    @BeforeEach
    void setUp() {
        service = new ChannelSettlementService(
                auditRepository, rpcClient, transactionBuilder, keypairService,
                NOOP_TRANSACTION_MANAGER,
                TREASURY, MOCK_PRIVATE_KEY);
    }

    @Test
    @DisplayName("settleChannel sweeps the cumulative amount, broadcasts, and transitions to SETTLED")
    void settleChannel_sweepsAndMarksSettled() {
        PaymentAuditRecord record = PaymentAuditRecord.create(
                "chan_1", "payerPubkey", 5_000L, 10L, "voucherSig", PaymentAuditStatus.VERIFIED);
        SolanaKeypair payer = realKeypairService.fromSeed(realKeypairService.deriveSeed("test-payer"));

        when(auditRepository.findTopByChannelIdOrderByNonceDesc("chan_1")).thenReturn(Optional.of(record));
        when(auditRepository.claimForSettlement(any(), eq(PaymentAuditStatus.VERIFIED), eq(PaymentAuditStatus.SETTLING)))
                .thenReturn(1);
        when(auditRepository.completeSettlement(any(), eq(PaymentAuditStatus.SETTLING),
                eq(PaymentAuditStatus.SETTLED), eq("mockTxSignature"))).thenReturn(1);
        when(rpcClient.getLatestBlockhash()).thenReturn(Optional.of(BLOCKHASH));
        when(keypairService.fromBase58SecretKey(MOCK_PRIVATE_KEY)).thenReturn(payer);
        when(transactionBuilder.serializeAndSign(anyList(), eq(BLOCKHASH), anyList())).thenReturn("base64Tx");
        when(rpcClient.sendTransaction("base64Tx")).thenReturn(Optional.of("mockTxSignature"));

        SettlementResult result = service.settleChannel("chan_1");

        assertThat(result.channelId()).isEqualTo("chan_1");
        assertThat(result.settledAmountAtomic()).isEqualTo(5_000L);
        assertThat(result.txSignature()).isEqualTo("mockTxSignature");
        assertThat(result.timestamp()).isPositive();

        // The entity is no longer mutated in memory: the guarded bulk transitions
        // are the authoritative state change.
        verify(auditRepository).claimForSettlement(any(), eq(PaymentAuditStatus.VERIFIED),
                eq(PaymentAuditStatus.SETTLING));
        verify(auditRepository).completeSettlement(any(), eq(PaymentAuditStatus.SETTLING),
                eq(PaymentAuditStatus.SETTLED), eq("mockTxSignature"));
        verify(auditRepository, never()).save(any(PaymentAuditRecord.class));

        ArgumentCaptor<List<SolanaInstruction>> instructions = ArgumentCaptor.forClass(List.class);
        verify(transactionBuilder).serializeAndSign(instructions.capture(), eq(BLOCKHASH), anyList());
        SolanaInstruction transfer = instructions.getValue().get(0);
        assertThat(readU64(transfer.data(), 4)).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("settleChannel fails when the channel has no audit record")
    void settleChannel_throwsWhenNoRecord() {
        when(auditRepository.findTopByChannelIdOrderByNonceDesc("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settleChannel("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No audit record");

        verifyNoInteractions(rpcClient, transactionBuilder, keypairService);
    }

    @Test
    @DisplayName("settleChannel fails when the highest-nonce record is already settled")
    void settleChannel_throwsWhenAlreadySettled() {
        PaymentAuditRecord record = PaymentAuditRecord.create(
                "chan_2", "payerPubkey", 5_000L, 10L, "voucherSig", PaymentAuditStatus.SETTLED);
        when(auditRepository.findTopByChannelIdOrderByNonceDesc("chan_2")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.settleChannel("chan_2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsettled");

        verifyNoInteractions(rpcClient, transactionBuilder, keypairService);
    }

    @Test
    @DisplayName("settleChannel releases the claim when a fresh blockhash cannot be fetched")
    void settleChannel_blockhashUnavailable_releasesClaim() {
        PaymentAuditRecord record = PaymentAuditRecord.create(
                "chan_3", "payerPubkey", 5_000L, 10L, "voucherSig", PaymentAuditStatus.VERIFIED);
        when(auditRepository.findTopByChannelIdOrderByNonceDesc("chan_3")).thenReturn(Optional.of(record));
        when(auditRepository.claimForSettlement(any(), eq(PaymentAuditStatus.VERIFIED), eq(PaymentAuditStatus.SETTLING)))
                .thenReturn(1);
        when(rpcClient.getLatestBlockhash()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settleChannel("chan_3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blockhash");

        // No broadcast was attempted, so the claim is released and the channel retryable.
        verify(auditRepository).releaseSettlementClaim(
                any(), eq(PaymentAuditStatus.SETTLING), eq(PaymentAuditStatus.VERIFIED));
        verify(auditRepository, never()).markSettlementFailed(any(), any(), any());
        verify(rpcClient, never()).sendTransaction(any());
    }

    @Test
    @DisplayName("settleChannel marks the record SETTLEMENT_FAILED on an ambiguous broadcast failure")
    void settleChannel_ambiguousBroadcastFailure_marksSettlementFailed() {
        PaymentAuditRecord record = PaymentAuditRecord.create(
                "chan_4", "payerPubkey", 5_000L, 10L, "voucherSig", PaymentAuditStatus.VERIFIED);
        SolanaKeypair payer = realKeypairService.fromSeed(realKeypairService.deriveSeed("test-payer"));

        when(auditRepository.findTopByChannelIdOrderByNonceDesc("chan_4")).thenReturn(Optional.of(record));
        when(auditRepository.claimForSettlement(any(), eq(PaymentAuditStatus.VERIFIED), eq(PaymentAuditStatus.SETTLING)))
                .thenReturn(1);
        when(rpcClient.getLatestBlockhash()).thenReturn(Optional.of(BLOCKHASH));
        when(keypairService.fromBase58SecretKey(MOCK_PRIVATE_KEY)).thenReturn(payer);
        when(transactionBuilder.serializeAndSign(anyList(), eq(BLOCKHASH), anyList())).thenReturn("base64Tx");
        when(rpcClient.sendTransaction("base64Tx")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settleChannel("chan_4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sendTransaction");

        // The record must never return to VERIFIED: an ambiguous broadcast could have
        // landed on-chain, so the claim moves to a terminal non-VERIFIED state.
        verify(auditRepository).markSettlementFailed(
                any(), eq(PaymentAuditStatus.SETTLING), eq(PaymentAuditStatus.SETTLEMENT_FAILED));
        verify(auditRepository, never()).releaseSettlementClaim(any(), any(), any());
        verify(auditRepository, never()).completeSettlement(any(), any(), any(), any());
    }

    @Test
    @DisplayName("settleChannel never broadcasts when another sweep already holds the claim")
    void settleChannel_neverBroadcastsWhenClaimLost() {
        PaymentAuditRecord record = PaymentAuditRecord.create(
                "chan_5", "payerPubkey", 5_000L, 10L, "voucherSig", PaymentAuditStatus.VERIFIED);
        when(auditRepository.findTopByChannelIdOrderByNonceDesc("chan_5")).thenReturn(Optional.of(record));
        when(auditRepository.claimForSettlement(any(), eq(PaymentAuditStatus.VERIFIED), eq(PaymentAuditStatus.SETTLING)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.settleChannel("chan_5"))
                .isInstanceOf(SettlementConflictException.class)
                .hasMessageContaining("already in progress");

        verifyNoInteractions(rpcClient, transactionBuilder, keypairService);
        verify(auditRepository, never()).completeSettlement(any(), any(), any(), any());
    }

    private static long readU64(byte[] data, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }
}

