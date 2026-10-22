package com.msb.solana.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.exception.DuplicateVoucherNonceException;
import com.msb.solana.gateway.model.PaymentRequiredChallenge;
import com.msb.solana.gateway.model.PaymentSettlementReceipt;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.service.ChannelVoucherVerifier;
import com.msb.solana.gateway.service.PaymentAuditService;
import com.msb.solana.gateway.service.X402ChallengeFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * x402 gatekeeper. Verifies off-chain payment vouchers in memory before the
 * paid resource is reached.
 *
 * <p>Failure classification is strict and fail-closed:
 * <ul>
 *   <li>No payment header &rarr; {@code 402 Payment Required} with the normative
 *       {@code PAYMENT-REQUIRED} challenge.</li>
 *   <li>Undecodable header &rarr; {@code 400} with a static, sanitized body.</li>
 *   <li>Invalid signature, replay, depleted channel, or a duplicate ledger nonce
 *       &rarr; {@code 403 Forbidden}.</li>
 *   <li>Unexpected internal failure &rarr; {@code 500} with a static body; the
 *       cause is logged server-side and never serialized.</li>
 * </ul>
 */
@Component
public class X402PaymentFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(X402PaymentFilter.class);

    private static final String PAYMENT_REJECTED_MESSAGE =
            "Signature invalid, replay detected, or ceiling exceeded";
    private static final String MALFORMED_HEADER_MESSAGE =
            "Payment header must be a Base64-encoded x402 PaymentPayload JSON document";
    private static final String INTERNAL_ERROR_MESSAGE = "An unexpected internal error occurred";

    private final ChannelVoucherVerifier voucherVerifier;
    private final PaymentAuditService auditService;
    private final ObjectMapper objectMapper;
    private final X402ChallengeFactory challengeFactory;

    public X402PaymentFilter(ChannelVoucherVerifier voucherVerifier,
                             PaymentAuditService auditService,
                             ObjectMapper objectMapper,
                             X402ChallengeFactory challengeFactory) {
        this.voucherVerifier = voucherVerifier;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.challengeFactory = challengeFactory;
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
        // Read-only cockpit observability endpoint; not a metered x402 resource.
        if (path.startsWith("/api/v1/rwa/audit")) {
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

        PaymentVoucher voucher;
        try {
            byte[] decodedJson = Base64.getDecoder().decode(paymentHeader.trim());
            voucher = objectMapper.readValue(decodedJson, PaymentVoucher.class);
        } catch (IllegalArgumentException | IOException ex) {
            log.warn("Rejected malformed x402 payment header: {}", ex.getClass().getSimpleName());
            sendError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "MALFORMED_PAYMENT_HEADER", MALFORMED_HEADER_MESSAGE);
            return;
        }

        try {
            if (!voucherVerifier.verifyVoucher(voucher, challengeFactory.priceAtomicUnits())) {
                log.warn("Rejected x402 voucher for channel {}", voucher.channelId());
                sendError(response, HttpServletResponse.SC_FORBIDDEN,
                        "PAYMENT_REJECTED", PAYMENT_REJECTED_MESSAGE);
                return;
            }

            // Append the verified voucher to the immutable audit ledger. The ledger's
            // unique (channel_id, nonce) constraint is the durable anti-replay authority.
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
        } catch (DuplicateVoucherNonceException ex) {
            log.warn("Rejected replayed x402 voucher nonce for channel {}", voucher.channelId());
            sendError(response, HttpServletResponse.SC_FORBIDDEN,
                    "PAYMENT_REJECTED", PAYMENT_REJECTED_MESSAGE);
            return;
        } catch (Exception ex) {
            log.error("Unexpected failure while processing an x402 payment", ex);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE);
            return;
        }

        // Downstream MVC exceptions must reach the DispatcherServlet's resolvers, so
        // the chain is deliberately invoked outside the payment try/catch.
        filterChain.doFilter(request, response);
    }

    private void sendError(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "error", error,
                "message", message)));
    }

    private void send402PaymentRequired(HttpServletResponse response) throws IOException {
        PaymentRequiredChallenge challenge = challengeFactory.createChallenge();
        String challengeJson = objectMapper.writeValueAsString(challenge);
        String encodedChallenge = Base64.getEncoder().encodeToString(challengeJson.getBytes(StandardCharsets.UTF_8));

        response.setStatus(402);
        response.setHeader("PAYMENT-REQUIRED", encodedChallenge);
        response.setHeader("X-PAYMENT-REQUIRED", encodedChallenge);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(challengeJson);
    }
}