package com.msb.solana.rwa.controller;

import com.msb.solana.gateway.SolanaPaymentGatewayApplication;
import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.rwa.entity.AuditLog;
import com.msb.solana.rwa.entity.AuditLogStatus;
import com.msb.solana.rwa.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the read-only cockpit endpoint is reachable without an x402 payment
 * voucher (the filter exempts {@code /api/v1/rwa/audit}) and returns the
 * aggregated dashboard contract the Angular cockpit consumes.
 */
@SpringBootTest(classes = SolanaPaymentGatewayApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditControllerIntegrationTest {

    private static final String PAYER = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PaymentAuditRepository paymentAuditRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        paymentAuditRepository.deleteAll();

        auditLogRepository.save(AuditLog.builder()
                .walletAddress(PAYER)
                .action("CHECK_ELIGIBILITY")
                .status(AuditLogStatus.APPROVED)
                .timestamp(Instant.now())
                .build());
        auditLogRepository.save(AuditLog.builder()
                .walletAddress(PAYER)
                .action("CHECK_ELIGIBILITY")
                .status(AuditLogStatus.BLOCKED)
                .reason("Investor KYC not complete")
                .timestamp(Instant.now())
                .build());

        PaymentAuditRecord settled = PaymentAuditRecord.create(
                "chan_audit_1", PAYER, 5000L, 1L, "voucherSig1", PaymentAuditStatus.VERIFIED);
        settled.markSettled("txSigSettled1");
        paymentAuditRepository.save(settled);
        paymentAuditRepository.save(PaymentAuditRecord.create(
                "chan_audit_2", PAYER, 5000L, 2L, "voucherSig2", PaymentAuditStatus.VERIFIED));
    }

    @Test
    @DisplayName("GET /api/v1/rwa/audit is not x402-gated and returns metrics + recent transactions")
    void getAudit_returnsDashboardWithoutPayment() throws Exception {
        mockMvc.perform(get("/api/v1/rwa/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.totalAttestations").value(2))
                .andExpect(jsonPath("$.metrics.x402PaymentsProcessed").value(2))
                .andExpect(jsonPath("$.metrics.averageLatencyMs").value(10.0))
                .andExpect(jsonPath("$.metrics.blockRatePercent").value(50.0))
                .andExpect(jsonPath("$.recentTransactions", hasSize(4)))
                .andExpect(jsonPath("$.recentTransactions[*].status",
                        hasItems("APPROVED", "BLOCKED", "VERIFIED", "SETTLED")))
                .andExpect(jsonPath("$.updatedAt").exists());
    }
}
