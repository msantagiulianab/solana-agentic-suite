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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

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

    @Test
    @DisplayName("Should match TypeScript golden vectors for canonical payload and Ed25519 signature")
    void testGoldenVectorParityWithTypeScript() throws Exception {
        // Golden vectors generated with the exact `smoke-test.sh` algorithm
        // (seed = sha256("smoke-test-payer-seed-v1"), channel "chan_smoke_test_001",
        // amount 5000, nonce 1). These pin the JVM to the TypeScript byte layout.
        String goldenPayerPubkey = "2uc1Wmo6jxvAo6mX1hTcYyswBeArV8mHc6aNz3wYeVtn";
        String goldenPayloadHex =
                "583430325f4348414e4e454c5f56313a13006368616e5f736d6f6b655f746573745f30303188130000000000000100000000000000";
        String goldenSigBase58 =
                "3zKcze3Q9DDRui2YCMeTPsBs3mxymxMN3oCvNDVKkrbgtZsR6CVfrLaMPd1kVHjoiAYEqTLNYxAzeABmdBMWSWhm";

        byte[] seed = MessageDigest.getInstance("SHA-256")
                .digest("smoke-test-payer-seed-v1".getBytes(StandardCharsets.UTF_8));

        Ed25519PrivateKeyParameters privKey = new Ed25519PrivateKeyParameters(seed, 0);
        Ed25519PublicKeyParameters pubKey = privKey.generatePublicKey();
        String payerPubkeyBase58 = Base58.encode(pubKey.getEncoded());
        assertEquals(goldenPayerPubkey, payerPubkeyBase58,
                "Derived payer public key must match the TypeScript golden vector");

        PaymentVoucher voucher = new PaymentVoucher(
                "chan_smoke_test_001",
                payerPubkeyBase58,
                5000L,
                1L,
                ""
        );

        byte[] canonicalBytes = voucher.getCanonicalPayload();
        String payloadHex = HexFormat.of().formatHex(canonicalBytes);
        assertEquals(goldenPayloadHex, payloadHex,
                "Canonical payload must match the TypeScript golden vector byte-for-byte");

        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privKey);
        signer.update(canonicalBytes, 0, canonicalBytes.length);
        String sigBase58 = Base58.encode(signer.generateSignature());
        assertEquals(goldenSigBase58, sigBase58,
                "Ed25519 signature must match the TypeScript golden vector");
    }
}