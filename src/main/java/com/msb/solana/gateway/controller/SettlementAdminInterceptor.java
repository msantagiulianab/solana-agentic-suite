package com.msb.solana.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Administrative access gate for the on-chain settlement sweep endpoints.
 *
 * <p>The mutating {@code POST /api/v1/settlement/**} routes are intentionally
 * excluded from the x402 payment filter, so this interceptor is the sole
 * authentication boundary for them. A valid {@code X-Admin-Key} header must match
 * {@code gateway.settlement.admin-key}; the comparison is constant-time so the
 * shared secret is not exposed through a timing side channel.
 */
@Component
public class SettlementAdminInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SettlementAdminInterceptor.class);

    private static final String DEFAULT_DEV_ADMIN_KEY = "dev-admin-key";
    private static final String ADMIN_HEADER = "X-Admin-Key";

    private final byte[] expectedKey;

    public SettlementAdminInterceptor(
            @Value("${gateway.settlement.admin-key:dev-admin-key}") String adminKey) {
        if (adminKey == null || adminKey.isBlank()) {
            throw new IllegalStateException("gateway.settlement.admin-key must not be blank");
        }
        if (DEFAULT_DEV_ADMIN_KEY.equals(adminKey)) {
            log.warn("Settlement admin gate is using the default dev admin key; "
                    + "override SETTLEMENT_ADMIN_KEY in every deployed environment");
        }
        this.expectedKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String provided = request.getHeader(ADMIN_HEADER);
        if (provided == null || provided.isBlank()
                || !MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected settlement request without a valid X-Admin-Key from {}", request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"A valid X-Admin-Key header is required\"}");
            return false;
        }
        return true;
    }
}
