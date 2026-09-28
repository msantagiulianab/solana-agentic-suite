package com.msb.solana.gateway.service;

import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.serialization.Ed25519SignatureVerifier;
import com.msb.solana.gateway.serialization.SolanaAddressValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChannelVoucherVerifier {
    private static final Logger log = LoggerFactory.getLogger(ChannelVoucherVerifier.class);

    private final Ed25519SignatureVerifier signatureVerifier;
    private final SolanaAddressValidator addressValidator;
    private final EscrowBalanceProvider escrowBalanceProvider;

    private final Map<String, Long> lastSeenNonces = new ConcurrentHashMap<>();

    public ChannelVoucherVerifier(Ed25519SignatureVerifier signatureVerifier,
                                  SolanaAddressValidator addressValidator,
                                  EscrowBalanceProvider escrowBalanceProvider) {
        this.signatureVerifier = signatureVerifier;
        this.addressValidator = addressValidator;
        this.escrowBalanceProvider = escrowBalanceProvider;
    }

    public boolean verifyVoucher(PaymentVoucher voucher, long requiredAmount) {
        if (voucher == null) {
            log.warn("Voucher rejection: null voucher payload");
            return false;
        }

        if (!addressValidator.isValid(voucher.payerPubkey())) {
            log.warn("Voucher rejection: Invalid payer Base58 public key: {}", voucher.payerPubkey());
            return false;
        }

        Long lastNonce = lastSeenNonces.getOrDefault(voucher.channelId(), 0L);
        if (voucher.nonce() <= lastNonce) {
            log.warn("Voucher replay detected for channel {}: incoming nonce {} <= last seen {}",
                    voucher.channelId(), voucher.nonce(), lastNonce);
            return false;
        }

        long verifiedDepositCeiling = escrowBalanceProvider.getVerifiedDepositCeiling(voucher.channelId());
        if (voucher.cumulativeAmountAtomic() > verifiedDepositCeiling) {
            log.warn("Voucher rejection: Cumulative spend {} exceeds verified escrow deposit ceiling {} for channel {}",
                    voucher.cumulativeAmountAtomic(), verifiedDepositCeiling, voucher.channelId());
            return false;
        }

        byte[] canonicalBytes = voucher.getCanonicalPayload();
        boolean validSig = signatureVerifier.verifyBase58(
                canonicalBytes,
                voucher.signature(),
                voucher.payerPubkey()
        );

        if (!validSig) {
            log.warn("Voucher rejection: Cryptographic signature mismatch for channel {}", voucher.channelId());
            return false;
        }

        lastSeenNonces.put(voucher.channelId(), voucher.nonce());
        return true;
    }

    /**
     * Package-private reset hook for test isolation between MockMvc runs.
     */
    public void resetState() {
        lastSeenNonces.clear();
    }
}