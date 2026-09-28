package com.msb.solana.gateway.controller;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end MockMvc integration test for the administrative settlement sweep
 * endpoint. Runs in mock RPC mode so the sweep transaction is built, signed,
 * and "broadcast" entirely in-process without any external Solana node.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettlementControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentAuditRepository auditRepository;

    private String channelId;

    @BeforeEach
    void setUp() {
        channelId = "settle_chan_" + System.nanoTime();
        PaymentAuditRecord record = PaymentAuditRecord.create(
                channelId, "payerPubkey", 5_000L, 1L, "voucherSig", PaymentAuditStatus.VERIFIED);
        auditRepository.save(record);
    }

    @AfterEach
    void cleanup() {
        auditRepository.findByChannelId(channelId).forEach(auditRepository::delete);
    }

    @Test
    @DisplayName("POST sweep settles the channel and persists the transaction signature")
    void sweepEndpoint_settlesChannelEndToEnd() throws Exception {
        mockMvc.perform(post("/api/v1/settlement/channels/{channelId}/sweep", channelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelId").value(channelId))
                .andExpect(jsonPath("$.settledAmountAtomic").value(5_000))
                .andExpect(jsonPath("$.txSignature").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNumber());

        PaymentAuditRecord settled = auditRepository.findByChannelId(channelId).get(0);
        assertThat(settled.getStatus()).isEqualTo(PaymentAuditStatus.SETTLED);
        assertThat(settled.getTxSignature()).isNotBlank();
    }
}
