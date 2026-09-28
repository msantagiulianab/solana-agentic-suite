package com.msb.solana.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.model.PaymentRequiredChallenge;
import com.msb.solana.gateway.model.PaymentSettlementReceipt;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.service.ChannelVoucherVerifier;
import com.msb.solana.gateway.service.PaymentAuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class X402PaymentFilter extends OncePerRequestFilter {

    private final ChannelVoucherVerifier voucherVerifier;
    private final PaymentAuditService auditService;
    private final ObjectMapper objectMapper;
    private final String escrowPubkey;
    private final long unitPriceAtomic;

    public X402PaymentFilter(
            ChannelVoucherVerifier voucherVerifier,
            PaymentAuditService auditService,
            ObjectMapper objectMapper,
            @Value("${x402.escrow-pubkey:7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU}") String escrowPubkey,
            @Value("${x402.price-atomic-units:5000}") long unitPriceAtomic) {
        this.voucherVerifier = voucherVerifier;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.escrowPubkey = escrowPubkey;
        this.unitPriceAtomic = unitPriceAtomic;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Administrative settlement sweeps are not paid x402 resources.
        if (path.startsWith("/api/v1/settlement/")) {
            return true;
        }
        // The machine-readable discovery document is public metadata; it must be
        // reachable without a payment voucher so clients can negotiate payment.
        if (path.startsWith("/.well-known/")) {
            return true;
        }
        return !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String paymentHeader = request.getHeader("PAYMENT-SIGNATURE");
        if (paymentHeader == null || paymentHeader.isBlank()) {
            paymentHeader = request.getHeader("X-PAYMENT");
        }

        if (paymentHeader == null || paymentHeader.isBlank()) {
            send402PaymentRequired(response);
            return;
        }

        try {
            byte[] decodedJson = Base64.getDecoder().decode(paymentHeader.trim());
            PaymentVoucher voucher = objectMapper.readValue(decodedJson, PaymentVoucher.class);

            boolean authorized = voucherVerifier.verifyVoucher(voucher, unitPriceAtomic);

            if (!authorized) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\": \"PAYMENT_REJECTED\", \"message\": \"Signature invalid, replay detected, or ceiling exceeded\"}");
                return;
            }

            // Append the verified voucher to the immutable audit ledger.
            auditService.recordVerifiedVoucher(voucher);

            PaymentSettlementReceipt receipt = new PaymentSettlementReceipt(
                    voucher.channelId(),
                    voucher.cumulativeAmountAtomic(),
                    voucher.nonce(),
                    System.currentTimeMillis(),
                    "VERIFIED"
            );
            String encodedReceipt = Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(receipt));
            response.setHeader("PAYMENT-RESPONSE", encodedReceipt);
            response.setHeader("X-PAYMENT-RESPONSE", encodedReceipt);

            filterChain.doFilter(request, response);

        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\": \"MALFORMED_PAYMENT_HEADER\", \"details\": \"" + e.getMessage() + "\"}");
        }
    }

    private void send402PaymentRequired(HttpServletResponse response) throws IOException {
        PaymentRequiredChallenge challenge = PaymentRequiredChallenge.defaultSolanaChallenge(escrowPubkey, unitPriceAtomic);
        String challengeJson = objectMapper.writeValueAsString(challenge);
        String encodedChallenge = Base64.getEncoder().encodeToString(challengeJson.getBytes(StandardCharsets.UTF_8));

        response.setStatus(402);
        response.setHeader("PAYMENT-REQUIRED", encodedChallenge);
        response.setHeader("X-PAYMENT-REQUIRED", encodedChallenge);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(challengeJson);
    }
}