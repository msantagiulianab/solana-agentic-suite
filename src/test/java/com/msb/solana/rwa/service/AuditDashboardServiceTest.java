package com.msb.solana.rwa.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.rwa.entity.AuditLog;
import com.msb.solana.rwa.entity.AuditLogStatus;
import com.msb.solana.rwa.model.AuditDashboardResponse;
import com.msb.solana.rwa.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditDashboardServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private PaymentAuditRepository paymentAuditRepository;

    private AuditDashboardService service;

    @BeforeEach
    void setUp() {
        service = new AuditDashboardService(auditLogRepository, paymentAuditRepository, 5.0, 50);
    }

    @Test
    @DisplayName("Aggregates attestation and x402 payment counts plus the fail-closed block rate")
    void getMetrics_aggregatesBothLedgers() {
        when(auditLogRepository.count()).thenReturn(3L);
        when(auditLogRepository.countByStatus(AuditLogStatus.BLOCKED)).thenReturn(1L);
        when(paymentAuditRepository.count()).thenReturn(7L);

        AuditDashboardResponse.Metrics metrics = service.getMetrics();

        assertThat(metrics.totalAttestations()).isEqualTo(3L);
        assertThat(metrics.x402PaymentsProcessed()).isEqualTo(7L);
        assertThat(metrics.averageLatencyMs()).isEqualTo(5.0);
        assertThat(metrics.blockRatePercent()).isEqualTo(33.3);
    }

    @Test
    @DisplayName("Zero attestations yields a zero block rate without divide-by-zero")
    void getMetrics_zeroAttestations_isZeroBlockRate() {
        when(auditLogRepository.count()).thenReturn(0L);
        when(paymentAuditRepository.count()).thenReturn(0L);

        AuditDashboardResponse.Metrics metrics = service.getMetrics();

        assertThat(metrics.blockRatePercent()).isZero();
    }

    @Test
    @DisplayName("Merges RWA attestations and x402 payments newest-first with mapped fields")
    void getRecentTransactions_mergesNewestFirst() {
        Instant now = Instant.now();
        AuditLog log = AuditLog.builder()
                .walletAddress("payer-rwa")
                .action("CHECK_ELIGIBILITY")
                .status(AuditLogStatus.BLOCKED)
                .timestamp(now)
                .build();
        PaymentAuditRecord payment = PaymentAuditRecord.create(
                "chan_x", "payer-x402", 5000L, 1L, "voucherSig", PaymentAuditStatus.VERIFIED);

        when(auditLogRepository.findAllByOrderByTimestampDesc(any(PageRequest.class)))
                .thenReturn(List.of(log));
        when(paymentAuditRepository.findAllByOrderByCreatedAtDesc(any(PageRequest.class)))
                .thenReturn(List.of(payment));

        List<AuditDashboardResponse.RecentTransaction> result = service.getRecentTransactions();

        assertThat(result).hasSize(2);

        // Newest first: the RWA attestation carries a timestamp; the unsaved
        // payment record has no createdAt and therefore sorts last.
        AuditDashboardResponse.RecentTransaction first = result.get(0);
        assertThat(first.source()).isEqualTo("RWA_ATTESTATION");
        assertThat(first.status()).isEqualTo("BLOCKED");
        assertThat(first.payerPublicKey()).isEqualTo("payer-rwa");
        assertThat(first.timestamp()).isEqualTo(now);

        AuditDashboardResponse.RecentTransaction second = result.get(1);
        assertThat(second.source()).isEqualTo("X402_PAYMENT");
        assertThat(second.status()).isEqualTo("VERIFIED");
        assertThat(second.payerPublicKey()).isEqualTo("payer-x402");
        assertThat(second.transactionHash()).isEqualTo("voucherSig");
    }
}
