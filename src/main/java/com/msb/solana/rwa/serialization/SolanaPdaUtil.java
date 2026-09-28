package com.msb.solana.rwa.serialization;

import net.i2p.crypto.eddsa.math.Curve;
import net.i2p.crypto.eddsa.math.GroupElement;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Derives Solana program-derived addresses (PDAs) for Token-2022 transfer hook
 * account lookups.
 *
 * <p>Mirrors {@code Pubkey.findProgramAddress} / {@code createProgramAddress}:
 * hash {@code seeds || bump || program_id || "ProgramDerivedAddress"} with
 * SHA-256 and accept the first candidate whose hash is <em>off</em> the Ed25519
 * curve (cannot collide with a real keypair). The off-curve check reuses the
 * {@code net.i2p.crypto.eddsa} decompressor already on the classpath.
 */
public final class SolanaPdaUtil {

    private static final byte[] PDA_MARKER =
            "ProgramDerivedAddress".getBytes(StandardCharsets.US_ASCII);

    private SolanaPdaUtil() {
    }

    /**
     * A derived program address and the bump seed that produced it.
     *
     * @param address 32-byte program-derived address (defensively copied)
     * @param bump    canonical bump seed in {@code [0, 255]}
     */
    public record Pda(byte[] address, int bump) {

        public Pda {
            if (address == null || address.length != 32) {
                throw new IllegalArgumentException("Program-derived address must be 32 bytes");
            }
            address = address.clone();
        }

        @Override
        public byte[] address() {
            return address.clone();
        }

        /** @return base58-encoded program-derived address */
        public String addressBase58() {
            return Base58Codec.encode(address);
        }
    }

    /**
     * Derives a program address for a single seed.
     *
     * @param seed      32-byte-max seed bytes
     * @param programId 32-byte program that owns the PDA
     * @return the off-curve program address and its bump seed
     */
    public static Pda findProgramAddress(byte[] seed, byte[] programId) {
        return findProgramAddress(List.of(seed), programId);
    }

    /**
     * Derives a program address for multiple seeds (concatenated on-chain).
     *
     * @param seeds     ordered seed byte arrays (each at most 32 bytes)
     * @param programId 32-byte program that owns the PDA
     * @return the off-curve program address and its bump seed
     */
    public static Pda findProgramAddress(List<byte[]> seeds, byte[] programId) {
        if (programId == null || programId.length != 32) {
            throw new IllegalArgumentException("Program id must be 32 bytes");
        }
        byte[] seedBytes = concatSeeds(seeds);
        for (int bump = 255; bump >= 0; bump--) {
            byte[] input = concat(seedBytes, new byte[]{(byte) bump}, programId, PDA_MARKER);
            byte[] hash = sha256(input);
            if (isOffCurve(hash)) {
                return new Pda(hash, bump);
            }
        }
        throw new IllegalStateException(
                "Unable to derive a program-derived address (no off-curve candidate found)");
    }

    private static byte[] concatSeeds(List<byte[]> seeds) {
        if (seeds == null) {
            return new byte[0];
        }
        int length = 0;
        for (byte[] seed : seeds) {
            if (seed == null || seed.length > 32) {
                throw new IllegalArgumentException("Each PDA seed must be non-null and at most 32 bytes");
            }
            length += seed.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] seed : seeds) {
            System.arraycopy(seed, 0, out, offset, seed.length);
            offset += seed.length;
        }
        return out;
    }

    /** @return true when the 32-byte candidate is not a valid Ed25519 curve point */
    static boolean isOffCurve(byte[] point) {
        return !isOnCurve(point);
    }

    /** @return true when the 32-byte candidate decompresses to a valid Ed25519 curve point */
    static boolean isOnCurve(byte[] point) {
        try {
            EdDSANamedCurveSpec spec = EdDSANamedCurveTable.getByName("Ed25519");
            Curve curve = spec.getCurve();
            GroupElement element = new GroupElement(curve, point);
            return element.isOnCurve();
        } catch (IllegalArgumentException ex) {
            // The eddsa decompressor throws when the point is off-curve or malformed.
            return false;
        }
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is unavailable", ex);
        }
    }

    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length += array.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, out, offset, array.length);
            offset += array.length;
        }
        return out;
    }
}
