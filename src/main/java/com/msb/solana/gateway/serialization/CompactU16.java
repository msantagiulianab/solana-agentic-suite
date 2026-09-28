package com.msb.solana.gateway.serialization;

/**
 * Zero-dependency Solana {@code compact-u16} (shortvec) length codec.
 *
 * <p>Solana uses a variable-length little-endian integer for account counts,
 * signature counts, instruction counts, and instruction data lengths. The
 * canonical encoding is:
 * <ul>
 *   <li>{@code 0x00..0x7F}            -&gt; 1 byte</li>
 *   <li>{@code 0x80..0x3FFF}          -&gt; 2 bytes</li>
 *   <li>{@code 0x4000..0x1FFFFF}      -&gt; 3 bytes</li>
 * </ul>
 */
public final class CompactU16 {

    private static final int MAX_THREE_BYTE_VALUE = 0x1FFFFF;

    private CompactU16() {
    }

    /**
     * Encodes a non-negative integer into 1, 2, or 3 bytes.
     *
     * @param value value to encode (must be {@code 0 <= value <= 0x1FFFFF})
     * @return compact-u16 encoded bytes
     */
    public static byte[] encode(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("compact-u16 cannot encode negative values");
        }
        if (value < 0x80) {
            return new byte[]{(byte) value};
        }
        if (value < 0x4000) {
            return new byte[]{
                    (byte) ((value & 0x7F) | 0x80),
                    (byte) (value >> 7)
            };
        }
        if (value <= MAX_THREE_BYTE_VALUE) {
            return new byte[]{
                    (byte) ((value & 0x7F) | 0x80),
                    (byte) (((value >> 7) & 0x7F) | 0x80),
                    (byte) (value >> 14)
            };
        }
        throw new IllegalArgumentException("compact-u16 overflow: " + value);
    }

    /**
     * Decodes a compact-u16 from {@code data} starting at {@code offset[0]},
     * advancing the offset past the consumed bytes.
     *
     * @param data   source bytes
     * @param offset mutable one-element offset cursor
     * @return decoded value
     */
    public static int decodeLength(byte[] data, int[] offset) {
        int first = data[offset[0]++] & 0xFF;
        if ((first & 0x80) == 0) {
            return first;
        }
        int second = data[offset[0]++] & 0xFF;
        if ((second & 0x80) == 0) {
            return (first & 0x7F) | (second << 7);
        }
        int third = data[offset[0]++] & 0xFF;
        return (first & 0x7F) | ((second & 0x7F) << 7) | (third << 14);
    }
}
