package com.msb.solana.rwa.serialization;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;

/**
 * Value object bundling a Solana Ed25519 public key (32 bytes) with its
 * in-memory signing key. Never persisted, logged, or serialized; the accessors
 * intentionally return defensive copies.
 */
public class SolanaKeypair {

    private final byte[] publicKey;
    private final EdDSAPrivateKey privateKey;

    public SolanaKeypair(byte[] publicKey, EdDSAPrivateKey privateKey) {
        if (publicKey == null || publicKey.length != 32) {
            throw new IllegalArgumentException("Public key must be 32 bytes");
        }
        this.publicKey = publicKey.clone();
        this.privateKey = privateKey;
    }

    /**
     * @return base58-encoded 32-byte public key (the mint/authority address)
     */
    public String getPublicKeyBase58() {
        return Base58Codec.encode(publicKey);
    }

    /**
     * @return defensive copy of the raw 32-byte public key
     */
    public byte[] getPublicKeyBytes() {
        return publicKey.clone();
    }

    /**
     * @return the EdDSA private key used for signing
     */
    public EdDSAPrivateKey getPrivateKey() {
        return privateKey;
    }
}