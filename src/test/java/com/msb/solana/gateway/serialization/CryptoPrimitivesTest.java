package com.msb.solana.gateway.serialization;

import com.msb.solana.gateway.model.PaymentVoucher;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class CryptoPrimitivesTest {

    private SolanaAddressValidator addressValidator;
    private Ed25519SignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        addressValidator = new SolanaAddressValidator();
        verifier = new Ed25519SignatureVerifier();
    }

    @Test
    @DisplayName("Should encode and decode Base58 deterministically")
    void testBase58RoundTrip() {
        byte[] payload = "SolanaPaymentGatewayTestBytes".getBytes();
        String encoded = Base58.encode(payload);
        byte[] decoded = Base58.decode(encoded);

        assertArrayEquals(payload, decoded);
    }

    @Test
    @DisplayName("Should validate correct 32-byte Base58 Solana public key")
    void testSolanaAddressValidation() {
        String systemProgram = "11111111111111111111111111111111";
        assertTrue(addressValidator.isValid(systemProgram));

        assertFalse(addressValidator.isValid("0OIlInvalidBase58Char"));
        assertFalse(addressValidator.isValid(null));
        assertFalse(addressValidator.isValid(""));
    }

    @Test
    @DisplayName("Should verify valid Ed25519 signature over canonical voucher payload")
    void testEd25519SignatureVerificationSuccess() {
        Ed25519KeyPairGenerator keyGen = new Ed25519KeyPairGenerator();
        keyGen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        var keyPair = keyGen.generateKeyPair();
        Ed25519PrivateKeyParameters privKey = (Ed25519PrivateKeyParameters) keyPair.getPrivate();
        Ed25519PublicKeyParameters pubKey = (Ed25519PublicKeyParameters) keyPair.getPublic();

        String payerPubkeyBase58 = Base58.encode(pubKey.getEncoded());

        PaymentVoucher voucher = new PaymentVoucher(
                "chan_test_001",
                payerPubkeyBase58,
                50000L,
                1L,
                ""
        );

        byte[] canonicalBytes = voucher.getCanonicalPayload();

        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privKey);
        signer.update(canonicalBytes, 0, canonicalBytes.length);
        byte[] rawSig = signer.generateSignature();
        String base58Sig = Base58.encode(rawSig);

        boolean verified = verifier.verifyBase58(canonicalBytes, base58Sig, payerPubkeyBase58);
        assertTrue(verified, "Signature over canonical voucher payload must be verified");
    }

    @Test
    @DisplayName("Should fail-closed on tampered voucher amount")
    void testEd25519SignatureTamperFailure() {
        Ed25519KeyPairGenerator keyGen = new Ed25519KeyPairGenerator();
        keyGen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        var keyPair = keyGen.generateKeyPair();
        Ed25519PrivateKeyParameters privKey = (Ed25519PrivateKeyParameters) keyPair.getPrivate();
        Ed25519PublicKeyParameters pubKey = (Ed25519PublicKeyParameters) keyPair.getPublic();

        String payerPubkey = Base58.encode(pubKey.getEncoded());

        PaymentVoucher original = new PaymentVoucher("chan_test_001", payerPubkey, 5000L, 1L, "");
        byte[] originalBytes = original.getCanonicalPayload();

        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privKey);
        signer.update(originalBytes, 0, originalBytes.length);
        String base58Sig = Base58.encode(signer.generateSignature());

        PaymentVoucher tampered = new PaymentVoucher("chan_test_001", payerPubkey, 1000L, 1L, base58Sig);
        byte[] tamperedBytes = tampered.getCanonicalPayload();

        boolean verified = verifier.verifyBase58(tamperedBytes, base58Sig, payerPubkey);
        assertFalse(verified, "Tampered payload must fail verification");
    }
}