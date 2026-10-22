package com.msb.solana.gateway.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import com.msb.solana.gateway.exception.DuplicateVoucherNonceException;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends successfully verified x402 vouchers to the immutable audit ledger.
 *
 * <p>The INSERT is flushed inside the transaction so the database-level
 * {@code (channel_id, nonce)} unique constraint is evaluated before the paid
 * resource is served. A constraint violation is translated into a typed
 * {@link DuplicateVoucherNonceException} (HTTP 403 for the caller), never a raw
 * persistence exception that could leak SQL text.
 */
@Service
public class PaymentAuditService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAuditService.class);

    private final PaymentAuditRepository repository;

    public PaymentAuditService(PaymentAuditRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PaymentAuditRecord recordVerifiedVoucher(PaymentVoucher voucher) {
        PaymentAuditRecord record = PaymentAuditRecord.create(
                voucher.channelId(),
                voucher.payerPubkey(),
                voucher.cumulativeAmountAtomic(),
                voucher.nonce(),
                voucher.signature(),
                PaymentAuditStatus.VERIFIED
        );

        try {
            PaymentAuditRecord saved = repository.saveAndFlush(record);
            log.info("Payment audit ledger appended: channel={} payer={} nonce={} cumulativeAtomic={}",
                    saved.getChannelId(), saved.getPayerPubkey(), saved.getNonce(), saved.getCumulativeAmountAtomic());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.warn("Rejected duplicate voucher nonce: channel={} nonce={}", voucher.channelId(), voucher.nonce());
            throw new DuplicateVoucherNonceException(
                    "Voucher nonce " + voucher.nonce() + " was already recorded for channel "
                            + voucher.channelId(), ex);
        }
    }
}
