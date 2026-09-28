package com.msb.solana.rwa.serialization;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Factory for Solana Compute Budget program instructions.
 *
 * <p>Compute Budget instructions are account-less: the Compute Budget program id
 * is compiled into the transaction message account table by the serializer as a
 * readonly, non-signer account, while the instruction itself carries an empty
 * account list.
 */
public final class ComputeBudgetInstruction {

    /**
     * Compute Budget program id (identical across all Solana clusters).
     */
    public static final String COMPUTE_BUDGET_PROGRAM_ID =
            "ComputeBudget111111111111111111111111111111";

    private static final byte SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR = 0x02;
    private static final byte SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR = 0x03;

    private ComputeBudgetInstruction() {
    }

    /**
     * Builds a {@code SetComputeUnitPrice} instruction: {@code 0x03} followed by
     * an 8-byte little-endian unsigned {@code u64} priority fee in micro-lamports.
     *
     * @param microLamports non-negative priority fee in micro-lamports per compute unit
     * @return account-less Compute Budget instruction
     */
    public static SolanaInstruction setComputeUnitPrice(long microLamports) {
        if (microLamports < 0) {
            throw new IllegalArgumentException("microLamports must be non-negative");
        }
        byte[] data = ByteBuffer.allocate(Byte.BYTES + Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(SET_COMPUTE_UNIT_PRICE_DISCRIMINATOR)
                .putLong(microLamports)
                .array();
        return new SolanaInstruction(
                Base58Codec.decode(COMPUTE_BUDGET_PROGRAM_ID), List.of(), data);
    }

    /**
     * Builds a {@code SetComputeUnitLimit} instruction: {@code 0x02} followed by
     * a 4-byte little-endian unsigned {@code u32} compute unit limit.
     *
     * @param units non-negative compute unit limit
     * @return account-less Compute Budget instruction
     */
    public static SolanaInstruction setComputeUnitLimit(int units) {
        if (units < 0) {
            throw new IllegalArgumentException("units must be non-negative");
        }
        byte[] data = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(SET_COMPUTE_UNIT_LIMIT_DISCRIMINATOR)
                .putInt(units)
                .array();
        return new SolanaInstruction(
                Base58Codec.decode(COMPUTE_BUDGET_PROGRAM_ID), List.of(), data);
    }
}
