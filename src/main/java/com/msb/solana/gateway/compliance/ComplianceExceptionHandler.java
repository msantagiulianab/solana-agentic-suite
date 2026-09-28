package com.msb.solana.gateway.compliance;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates compliance validation failures into clean HTTP responses. The
 * error body preserves the legacy controller shape so existing clients are
 * unaffected.
 */
@RestControllerAdvice
public class ComplianceExceptionHandler {

    @ExceptionHandler(InvalidSolanaAddressException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidSolanaAddress(InvalidSolanaAddressException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "REJECTED",
                "error", "Invalid Solana Base58 address format",
                "message", ex.getMessage()));
    }
}
