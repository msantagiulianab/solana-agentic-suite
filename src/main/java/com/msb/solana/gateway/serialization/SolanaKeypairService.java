package com.msb.solana.gateway.serialization;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Derives Solana Ed25519 keypairs from raw seeds and signs transaction messages
 * in-process using BouncyCastle. No private key is ever persisted or logged.
 */
@Service
public class SolanaKeypairService {

    private static final int SEED_LENGTH = 32;
    private static final int PHANTOM_SECRET_KEY_LENGTH = 64;

    /**
     * Builds a keypair from a raw 32-byte Ed25519 seed.
     */
    public SolanaKeypair fromSeed(byte[] seed) {
        if (seed == null || seed.length != SEED_LENGTH) {
            throw new IllegalArgumentException("Solana seed must be 32 bytes");
        }
        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(seed, 0);
        Ed25519PublicKeyParameters publicKey = privateKey.generatePublicKey();
        return new SolanaKeypair(publicKey.getEncoded(), privateKey);
    }

    /**
     * Parses a configured Base58 secret key into a keypair. Accepts a 32-byte
     * seed or a 64-byte Phantom/CLI export (first 32 bytes are the seed).
     * Surrounding whitespace and matching quotes are tolerated.
     */
    public SolanaKeypair fromBase58SecretKey(String base58SecretKey) {
        String value = base58SecretKey == null ? "" : base58SecretKey.trim();
        while (value.length() >= 2
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.isEmpty()) {
            throw new IllegalStateException("Solana private key must not be blank");
        }

        byte[] decoded = Base58.decode(value);
        if (decoded.length == SEED_LENGTH) {
            return fromSeed(decoded);
        }
        if (decoded.length == PHANTOM_SECRET_KEY_LENGTH) {
            return fromSeed(Arrays.copyOf(decoded, SEED_LENGTH));
        }
        throw new IllegalStateException(
                "Solana private key must be a 32-byte or 64-byte key (found " + decoded.length + " bytes)");
    }

    /**
     * Signs an arbitrary-length message (the serialized Solana transaction
     * message) with the keypair.
     *
     * @return 64-byte Ed25519 signature
     */
    public byte[] sign(byte[] message, SolanaKeypair keypair) {
        if (message == null || message.length == 0) {
            throw new IllegalArgumentException("Solana signature message must not be empty");
        }
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, keypair.getPrivateKey());
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    /**
     * Creates a deterministic 32-byte seed from a UTF-8 string. Used to derive
     * test keypairs inline without committing real secret keys.
     */
    public byte[] deriveSeed(String material) {
        byte[] bytes = material.getBytes(StandardCharsets.UTF_8);
        byte[] seed = new byte[SEED_LENGTH];
        Arrays.fill(seed, (byte) 0);
        for (int i = 0; i < bytes.length; i++) {
            seed[i % SEED_LENGTH] ^= bytes[i];
        }
        return seed;
    }
}
