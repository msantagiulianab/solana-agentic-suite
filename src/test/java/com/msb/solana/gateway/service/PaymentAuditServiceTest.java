package com.msb.solana.gateway.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import com.msb.solana.gateway.exception.DuplicateVoucherNonceException;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAuditServiceTest {

    @Mock
    private PaymentAuditRepository repository;

    @InjectMocks
    private PaymentAuditService service;

    @Test
    @DisplayName("Should map a verified voucher to an append-only record and persist it")
    void recordVerifiedVoucher_mapsVoucherAndPersists() {
        PaymentVoucher voucher = new PaymentVoucher(
                "chan_1", "payerPubkeyBase58", 5000L, 1L, "signatureBase58");

        when(repository.saveAndFlush(any(PaymentAuditRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentAuditRecord result = service.recordVerifiedVoucher(voucher);

        assertThat(result.getChannelId()).isEqualTo("chan_1");
        assertThat(result.getPayerPubkey()).isEqualTo("payerPubkeyBase58");
        assertThat(result.getCumulativeAmountAtomic()).isEqualTo(5000L);
        assertThat(result.getNonce()).isEqualTo(1L);
        assertThat(result.getSignature()).isEqualTo("signatureBase58");
        assertThat(result.getStatus()).isEqualTo(PaymentAuditStatus.VERIFIED);

        verify(repository).saveAndFlush(any(PaymentAuditRecord.class));
    }

    @Test
    @DisplayName("A unique-constraint violation is translated into a typed duplicate-nonce failure")
    void recordVerifiedVoucher_duplicateNonce_throwsTypedException() {
        PaymentVoucher voucher = new PaymentVoucher(
                "chan_1", "payerPubkeyBase58", 5000L, 1L, "signatureBase58");

        when(repository.saveAndFlush(any(PaymentAuditRecord.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "could not execute statement; SQL [insert into payment_audit_ledger ...]; "
                                + "constraint [uk_payment_audit_ledger_channel_nonce]"));

        assertThatThrownBy(() -> service.recordVerifiedVoucher(voucher))
                .isInstanceOf(DuplicateVoucherNonceException.class)
                .hasMessageContaining("already recorded")
                .hasMessageNotContaining("SQL")
                .hasMessageNotContaining("constraint");
    }
}
