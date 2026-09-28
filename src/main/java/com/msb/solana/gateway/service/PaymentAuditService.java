package com.msb.solana.gateway.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends successfully verified x402 vouchers to the immutable audit ledger.
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

        PaymentAuditRecord saved = repository.save(record);
        log.info("Payment audit ledger appended: channel={} payer={} nonce={} cumulativeAtomic={}",
                saved.getChannelId(), saved.getPayerPubkey(), saved.getNonce(), saved.getCumulativeAmountAtomic());
        return saved;
    }
}
