package com.msb.solana.gateway.exception;

import com.msb.solana.gateway.compliance.InvalidSolanaAddressException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding #5: the web layer must never serialize internal exception text.
 */
class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    @DisplayName("An unexpected exception becomes a static 500 without internal details")
    void unexpectedFailure_isSanitized500() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(
                new RuntimeException("jdbc:postgresql://internal-db:5432/secret at com.msb.Foo"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "INTERNAL_ERROR");
        assertThat(String.valueOf(response.getBody()))
                .doesNotContain("jdbc")
                .doesNotContain("secret")
                .doesNotContain("RuntimeException")
                .doesNotContain("com.msb");
    }

    @Test
    @DisplayName("A duplicate voucher nonce becomes 403 PAYMENT_REJECTED with a static body")
    void duplicateNonce_is403() {
        ResponseEntity<Map<String, Object>> response = handler.handleDuplicateVoucherNonce(
                new DuplicateVoucherNonceException("duplicate key violates uk_payment_audit_ledger_channel_nonce"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "PAYMENT_REJECTED");
        assertThat(String.valueOf(response.getBody())).doesNotContain("duplicate key");
    }

    @Test
    @DisplayName("A settlement conflict becomes 409 CHANNEL_ALREADY_SETTLED")
    void settlementConflict_is409() {
        ResponseEntity<Map<String, Object>> response = handler.handleSettlementConflict(
                new SettlementConflictException("Channel settlement is already in progress or completed: chan_1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "CHANNEL_ALREADY_SETTLED");
    }

    @Test
    @DisplayName("An invalid Solana address becomes 400 with the legacy REJECTED contract")
    void invalidAddress_is400() {
        ResponseEntity<Map<String, Object>> response = handler.handleInvalidSolanaAddress(
                new InvalidSolanaAddressException("Invalid Solana Base58 address format"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("status", "REJECTED");
    }
}
