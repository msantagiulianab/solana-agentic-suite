package com.msb.solana.gateway.exception;

import com.msb.solana.gateway.compliance.InvalidSolanaAddressException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Sanitizing exception boundary for the HTTP surface.
 *
 * <p>Every error body is a controlled, client-safe contract. Internal exception
 * messages, SQL text, class names, and stack traces are logged server-side and
 * never serialized to the caller (OWASP error-handling baseline).
 *
 * <p>Payment-filter failures are handled inside the filter itself because a
 * servlet {@code Filter} runs before the {@code DispatcherServlet}; the handlers
 * here cover controller and persistence exceptions that escape a handler method.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(InvalidSolanaAddressException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidSolanaAddress(InvalidSolanaAddressException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "REJECTED",
                "error", "Invalid Solana Base58 address format",
                "message", messageOr(ex.getMessage(), "Invalid Solana Base58 address format")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBeanValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("request payload is invalid");
        return ResponseEntity.badRequest().body(Map.of(
                "status", "REJECTED",
                "error", "Invalid request payload",
                "message", detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Rejected unreadable request body: {}", ex.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(Map.of(
                "status", "REJECTED",
                "error", "Invalid request payload",
                "message", "Request body is not valid JSON"));
    }

    @ExceptionHandler(DuplicateVoucherNonceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateVoucherNonce(DuplicateVoucherNonceException ex) {
        log.warn("Rejected duplicate voucher nonce at the HTTP boundary: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "PAYMENT_REJECTED",
                "message", "Signature invalid, replay detected, or ceiling exceeded"));
    }

    @ExceptionHandler(SettlementConflictException.class)
    public ResponseEntity<Map<String, Object>> handleSettlementConflict(SettlementConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CHANNEL_ALREADY_SETTLED",
                "message", messageOr(ex.getMessage(), "Channel settlement is already in progress or completed")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception escaped the web layer", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL_ERROR",
                "message", "An unexpected internal error occurred"));
    }

    private String messageOr(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }
}
