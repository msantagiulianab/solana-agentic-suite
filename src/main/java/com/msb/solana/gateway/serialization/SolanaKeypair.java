package com.msb.solana.gateway.serialization;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

/**
 * Value object bundling a Solana Ed25519 public key (32 bytes) with its
 * in-memory BouncyCastle signing key. Never persisted, logged, or serialized;
 * the public-key accessor intentionally returns a defensive copy.
 */
public final class SolanaKeypair {

    private final byte[] publicKey;
    private final Ed25519PrivateKeyParameters privateKey;

    public SolanaKeypair(byte[] publicKey, Ed25519PrivateKeyParameters privateKey) {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("Public key must be 32 bytes");
        }
        if (privateKey == null) {
            throw new IllegalArgumentException("Private key must not be null");
        }
        this.publicKey = publicKey.clone();
        this.privateKey = privateKey;
    }

    /**
     * @return base58-encoded 32-byte public key
     */
    public String getPublicKeyBase58() {
        return Base58.encode(publicKey);
    }

    /**
     * @return defensive copy of the raw 32-byte public key
     */
    public byte[] getPublicKeyBytes() {
        return publicKey.clone();
    }

    /**
     * @return the in-memory Ed25519 private key used for signing
     */
    public Ed25519PrivateKeyParameters getPrivateKey() {
        return privateKey;
    }
}
