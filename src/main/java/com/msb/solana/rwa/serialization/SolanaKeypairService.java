package com.msb.solana.rwa.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Generates and signs Solana Ed25519 keypairs.
 *
 * <p>The signing key may be supplied through the {@code solana.rpc.private-key}
 * env var, or generated fresh per mint when left blank. The configured value is
 * parsed flexibly (see {@link #parseSecretKeyToSeed(String)}). No private key is
 * ever logged or persisted; only the derived public key is exposed at startup.
 */
@Slf4j
@Service("rwaSolanaKeypairService")
public class SolanaKeypairService {

    private static final int SEED_LENGTH = 32;
    private static final int PHANTOM_SECRET_KEY_LENGTH = 64;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName("Ed25519");

    private final String configuredPrivateKey;
    private final byte[] ephemeralSeed;
    private final SecureRandom secureRandom = new SecureRandom();

    public SolanaKeypairService(@Value("${solana.rpc.private-key:}") String configuredPrivateKey) {
        this.configuredPrivateKey = configuredPrivateKey;
        byte[] seed = new byte[SEED_LENGTH];
        this.secureRandom.nextBytes(seed);
        this.ephemeralSeed = seed;
    }

    /**
     * Resolves the keypair used for mint creation.
     *
     * <p>When {@code SOLANA_DEVNET_PRIVATE_KEY} is configured, it is decoded
     * into a 32-byte Ed25519 seed. Otherwise a fresh ephemeral keypair is used.
     */
    public SolanaKeypair resolveKeypair() {
        if (configuredPrivateKey != null && !configuredPrivateKey.isBlank()) {
            return fromSeed(parseSecretKeyToSeed(configuredPrivateKey));
        }
        return fromSeed(ephemeralSeed.clone());
    }

    /**
     * Parses a configured private key into a 32-byte Ed25519 seed.
     *
     * <p>Accepted formats:
     * <ul>
     *   <li>Base58 32-byte seed</li>
     *   <li>Base58 64-byte Phantom/CLI secret key (first 32 bytes are the seed)</li>
     *   <li>JSON integer byte array (Solana CLI keypair export)</li>
     * </ul>
     *
     * Leading/trailing whitespace and matching double- or single-quotes are
     * stripped before parsing to tolerate values copied from shell/JSON files.
     */
    byte[] parseSecretKeyToSeed(String rawPrivateKey) {
        String value = rawPrivateKey.trim();
        while (value.length() >= 2
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            value = value.substring(1, value.length() - 1).trim();
        }

        byte[] decoded;
        if (value.startsWith("[") && value.endsWith("]")) {
            decoded = parseJsonByteArray(value);
        } else {
            decoded = Base58Codec.decode(value);
        }
        return normalizeSeed(decoded);
    }

    private byte[] parseJsonByteArray(String json) {
        try {
            int[] values = OBJECT_MAPPER.readValue(json, int[].class);
            byte[] bytes = new byte[values.length];
            for (int i = 0; i < values.length; i++) {
                if (values[i] < Byte.MIN_VALUE || values[i] > 255) {
                    throw new IllegalStateException(
                            "SOLANA_DEVNET_PRIVATE_KEY JSON array contains an out-of-range byte at index "
                                    + i + ": " + values[i]);
                }
                bytes[i] = (byte) values[i];
            }
            return bytes;
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "SOLANA_DEVNET_PRIVATE_KEY must be a valid JSON byte array", ex);
        }
    }

    private byte[] normalizeSeed(byte[] decoded) {
        if (decoded.length == SEED_LENGTH) {
            return decoded;
        }
        if (decoded.length == PHANTOM_SECRET_KEY_LENGTH) {
            return Arrays.copyOf(decoded, SEED_LENGTH);
        }
        throw new IllegalStateException(
                "SOLANA_DEVNET_PRIVATE_KEY must be a 32-byte or 64-byte key (found "
                        + decoded.length + " bytes)");
    }

    /**
     * Logs the derived Devnet fee payer public key at startup so operators can
     * fund it before attempting any on-chain mint.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logFeePayerAddress() {
        try {
            String payer = resolveKeypair().getPublicKeyBase58();
            log.info("===========================================================");
            log.info("SOLANA DEVNET FEE PAYER PUBLIC KEY: {}", payer);
            log.info("Ensure this wallet has Devnet SOL via https://faucet.solana.com");
            log.info("===========================================================");
        } catch (Exception ex) {
            log.warn("Could not derive Solana Devnet fee payer address", ex);
        }
    }

    /**
     * Generates a fresh random keypair. Used for on-chain accounts (e.g. the
     * SPL mint account address) that must be unique per tokenized asset.
     */
    public SolanaKeypair generateKeypair() {
        byte[] seed = new byte[SEED_LENGTH];
        secureRandom.nextBytes(seed);
        return fromSeed(seed);
    }

    /**
     * Builds a keypair from a raw 32-byte Ed25519 seed.
     */
    public SolanaKeypair fromSeed(byte[] seed) {
        if (seed.length != SEED_LENGTH) {
            throw new IllegalArgumentException("Solana seed must be 32 bytes");
        }
        EdDSAPrivateKeySpec privateKeySpec = new EdDSAPrivateKeySpec(seed, spec);
        EdDSAPrivateKey privateKey = new EdDSAPrivateKey(privateKeySpec);
        EdDSAPublicKeySpec publicKeySpec = new EdDSAPublicKeySpec(privateKey.getA(), spec);
        EdDSAPublicKey publicKey = new EdDSAPublicKey(publicKeySpec);

        byte[] publicKeyBytes = publicKey.getAbyte().clone();
        return new SolanaKeypair(publicKeyBytes, privateKey);
    }

    /**
     * Signs an arbitrary-length message (the serialized Solana transaction
     * message) with the keypair.
     *
     * @return 64-byte Ed25519 signature
     */
    public byte[] sign(byte[] message, SolanaKeypair keypair) {
        if (message.length == 0) {
            throw new IllegalArgumentException("Solana signature message must not be empty");
        }
        try {
            EdDSAPrivateKey privateKey = keypair.getPrivateKey();
            EdDSAEngine signer = new EdDSAEngine();
            signer.initSign(privateKey);
            signer.update(message);
            return signer.sign();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign Solana transaction message", ex);
        }
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