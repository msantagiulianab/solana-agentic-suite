package com.msb.solana.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.exception.DuplicateVoucherNonceException;
import com.msb.solana.gateway.model.PaymentRequiredChallenge;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.service.ChannelVoucherVerifier;
import com.msb.solana.gateway.service.PaymentAuditService;
import com.msb.solana.gateway.service.X402ChallengeFactory;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Finding #5: the payment filter previously mapped every failure to
 * {@code 400 MALFORMED_PAYMENT_HEADER} and echoed {@code e.getMessage()} in the
 * body. This matrix pins the correct status per failure class and asserts that no
 * internal detail (SQL text, class names, exception messages) is ever serialized.
 */
class X402PaymentFilterTest {

    private static final String SCREEN_PATH = "/api/v1/compliance/screen-address";
    private static final String PAYER = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChannelVoucherVerifier voucherVerifier;
    private PaymentAuditService auditService;
    private X402ChallengeFactory challengeFactory;
    private X402PaymentFilter filter;

    @BeforeEach
    void setUp() {
        voucherVerifier = Mockito.mock(ChannelVoucherVerifier.class);
        auditService = Mockito.mock(PaymentAuditService.class);
        challengeFactory = Mockito.mock(X402ChallengeFactory.class);
        filter = new X402PaymentFilter(voucherVerifier, auditService, objectMapper, challengeFactory);
    }

    private MockHttpServletRequest request(String paymentHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", SCREEN_PATH);
        request.setRequestURI(SCREEN_PATH);
        request.setContentType("application/json");
        request.setContent("{\"address\":\"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y\"}".getBytes());
        if (paymentHeader != null) {
            request.addHeader("PAYMENT-SIGNATURE", paymentHeader);
        }
        return request;
    }

    private String validHeader() throws Exception {
        PaymentVoucher voucher = new PaymentVoucher("chan_1", PAYER, 5_000L, 1L, "signatureBase58");
        return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(voucher));
    }

    private MockHttpServletResponse dispatch(String paymentHeader) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request(paymentHeader), response, Mockito.mock(FilterChain.class));
        return response;
    }

    @Test
    @DisplayName("A missing payment header returns 402 with the factory-built challenge")
    void missingHeader_returns402WithFactoryChallenge() throws Exception {
        PaymentRequiredChallenge challenge = new PaymentRequiredChallenge(
                2, "channel", "solana:testnet", "escrowX", "SOL", 777L, "per-request", "pay");
        when(challengeFactory.createChallenge()).thenReturn(challenge);

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.doFilterInternal(request(null), response, chain);

        assertThat(response.getStatus()).isEqualTo(402);
        assertThat(response.getHeader("PAYMENT-REQUIRED")).isNotBlank();
        assertThat(response.getHeader("X-PAYMENT-REQUIRED")).isNotBlank();
        assertThat(response.getContentAsString())
                .contains("solana:testnet")
                .contains("SOL")
                .contains("per-request");
        verifyNoInteractions(voucherVerifier, auditService);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("A malformed Base64 header returns a sanitized 400 (no details field)")
    void malformedHeader_returnsSanitized400() throws Exception {
        MockHttpServletResponse response = dispatch("!!!not-base64!!!");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString())
                .contains("MALFORMED_PAYMENT_HEADER")
                .doesNotContain("Illegal")
                .doesNotContain("base64 character")
                .doesNotContain("java.")
                .doesNotContain("details");
        verifyNoInteractions(voucherVerifier, auditService);
    }

    @Test
    @DisplayName("A rejected voucher returns 403 and never reaches the audit ledger")
    void rejectedVoucher_returns403() throws Exception {
        when(challengeFactory.priceAtomicUnits()).thenReturn(5_000L);
        when(voucherVerifier.verifyVoucher(any(), anyLong())).thenReturn(false);

        MockHttpServletResponse response = dispatch(validHeader());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("PAYMENT_REJECTED");
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("A duplicate ledger nonce returns 403 PAYMENT_REJECTED, not 400")
    void duplicateNonce_returns403() throws Exception {
        when(challengeFactory.priceAtomicUnits()).thenReturn(5_000L);
        when(voucherVerifier.verifyVoucher(any(), anyLong())).thenReturn(true);
        when(auditService.recordVerifiedVoucher(any()))
                .thenThrow(new DuplicateVoucherNonceException(
                        "duplicate key value violates constraint uk_payment_audit_ledger_channel_nonce"));

        MockHttpServletResponse response = dispatch(validHeader());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("PAYMENT_REJECTED")
                .doesNotContain("constraint")
                .doesNotContain("duplicate key");
    }

    @Test
    @DisplayName("An unexpected audit failure returns a sanitized 500, never a 200 or leaked 400")
    void unexpectedFailure_returnsSanitized500() throws Exception {
        when(challengeFactory.priceAtomicUnits()).thenReturn(5_000L);
        when(voucherVerifier.verifyVoucher(any(), anyLong())).thenReturn(true);
        when(auditService.recordVerifiedVoucher(any()))
                .thenThrow(new RuntimeException("jdbc:postgresql://internal-db:5432 secret"));

        MockHttpServletResponse response = dispatch(validHeader());

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString())
                .contains("INTERNAL_ERROR")
                .doesNotContain("jdbc")
                .doesNotContain("secret")
                .doesNotContain("RuntimeException");
    }

    @Test
    @DisplayName("A valid voucher sets the PAYMENT-RESPONSE receipt and continues the chain")
    void validVoucher_setsReceiptAndContinues() throws Exception {
        when(challengeFactory.priceAtomicUnits()).thenReturn(5_000L);
        when(voucherVerifier.verifyVoucher(any(), anyLong())).thenReturn(true);

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.doFilterInternal(request(validHeader()), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("PAYMENT-RESPONSE")).isNotBlank();
        assertThat(response.getHeader("X-PAYMENT-RESPONSE")).isNotBlank();
        verify(auditService).recordVerifiedVoucher(any());
        verify(chain).doFilter(any(), any());
    }
}
