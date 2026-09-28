package com.msb.solana.gateway.serialization;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Byte-level tests for {@link CompactU16} and the pure-Java
 * {@link SolanaWireTransactionBuilder} wire serialization.
 */
class SolanaWireTransactionBuilderTest {

    private static final String BLOCKHASH = "11111111111111111111111111111111";
    private static final String TREASURY = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";

    private final SolanaKeypairService keypairService = new SolanaKeypairService();
    private final SolanaWireTransactionBuilder builder = new SolanaWireTransactionBuilder(keypairService);

    @Test
    @DisplayName("compact-u16 encodes 1-byte, 2-byte, and 3-byte boundaries")
    void compactU16_encodesBoundaries() {
        assertThat(CompactU16.encode(0)).containsExactly(0x00);
        assertThat(CompactU16.encode(0x7F)).containsExactly(0x7F);
        assertThat(CompactU16.encode(0x80)).containsExactly(0x80, 0x01);
        assertThat(CompactU16.encode(0x3FFF)).containsExactly(0xFF, 0x7F);
        assertThat(CompactU16.encode(0x4000)).containsExactly(0x80, 0x80, 0x01);
        assertThat(CompactU16.encode(0x1FFFFF)).containsExactly(0xFF, 0xFF, 0x7F);
    }

    @Test
    @DisplayName("compact-u16 round-trips decoded values and advances the cursor")
    void compactU16_roundTrips() {
        byte[] encoded = CompactU16.encode(5_000);
        int[] offset = {0};

        assertThat(CompactU16.decodeLength(encoded, offset)).isEqualTo(5_000);
        assertThat(offset[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("system transfer serializes a signed legacy transaction with canonical account ordering")
    void systemTransfer_serializesValidWireFormat() {
        SolanaKeypair payer = keypairService.fromSeed(keypairService.deriveSeed("test-payer"));
        SolanaKeypair treasury = keypairService.fromSeed(keypairService.deriveSeed("test-treasury"));

        SolanaInstruction transfer = SolanaWireTransactionBuilder.systemTransfer(
                payer.getPublicKeyBytes(), treasury.getPublicKeyBytes(), 123_456_789L);

        String encoded = builder.serializeAndSign(List.of(transfer), BLOCKHASH, List.of(payer));
        byte[] transaction = Base64.getDecoder().decode(encoded);

        int[] offset = {0};

        // Signature section: compact-u16 count then 64 bytes per signature.
        int signatureCount = CompactU16.decodeLength(transaction, offset);
        assertThat(signatureCount).isEqualTo(1);
        offset[0] += signatureCount * 64;

        // Message header: requiredSignatures, readonlySigned, readonlyUnsigned.
        int requiredSignatures = readU8(transaction, offset);
        int readonlySigned = readU8(transaction, offset);
        int readonlyUnsigned = readU8(transaction, offset);
        assertThat(requiredSignatures).isEqualTo(1);
        assertThat(readonlySigned).isZero();
        assertThat(readonlyUnsigned).isEqualTo(1);

        // Account table: payer (writable signer), treasury (writable), system program (readonly).
        int accountCount = CompactU16.decodeLength(transaction, offset);
        assertThat(accountCount).isEqualTo(3);
        byte[] payerAccount = readBytes(transaction, offset, 32);
        byte[] treasuryAccount = readBytes(transaction, offset, 32);
        byte[] systemProgram = readBytes(transaction, offset, 32);
        assertThat(Base58.encode(payerAccount)).isEqualTo(payer.getPublicKeyBase58());
        assertThat(Base58.encode(treasuryAccount)).isEqualTo(treasury.getPublicKeyBase58());
        assertThat(Base58.encode(systemProgram)).isEqualTo(SolanaWireTransactionBuilder.SYSTEM_PROGRAM_ID);

        // Recent blockhash (32 bytes).
        byte[] blockhash = readBytes(transaction, offset, 32);
        assertThat(Base58.encode(blockhash)).isEqualTo(BLOCKHASH);

        // Single instruction.
        int instructionCount = CompactU16.decodeLength(transaction, offset);
        assertThat(instructionCount).isEqualTo(1);

        int programIndex = readU8(transaction, offset);
        assertThat(programIndex).isEqualTo(2);

        int accountIndexCount = CompactU16.decodeLength(transaction, offset);
        assertThat(accountIndexCount).isEqualTo(2);
        assertThat(readU8(transaction, offset)).isZero(); // payer
        assertThat(readU8(transaction, offset)).isEqualTo(1); // treasury

        int dataLength = CompactU16.decodeLength(transaction, offset);
        assertThat(dataLength).isEqualTo(12);
        byte[] data = readBytes(transaction, offset, dataLength);
        assertThat(readU32(data, 0)).isEqualTo(2); // Transfer discriminator
        assertThat(readU64(data, 4)).isEqualTo(123_456_789L); // lamports little-endian

        assertThat(offset[0]).isEqualTo(transaction.length);
    }

    @Test
    @DisplayName("serializeAndSign rejects a blockhash that does not decode to 32 bytes")
    void serializeAndSign_rejectsInvalidBlockhash() {
        SolanaKeypair payer = keypairService.fromSeed(keypairService.deriveSeed("test-payer"));
        SolanaInstruction transfer = SolanaWireTransactionBuilder.systemTransfer(
                payer.getPublicKeyBytes(), Base58.decode(TREASURY), 1L);

        assertThatThrownBy(() ->
                        builder.serializeAndSign(List.of(transfer), "short", List.of(payer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    private static int readU8(byte[] data, int[] offset) {
        return data[offset[0]++] & 0xFF;
    }

    private static byte[] readBytes(byte[] data, int[] offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset[0], out, 0, length);
        offset[0] += length;
        return out;
    }

    private static int readU32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static long readU64(byte[] data, int offset) {
        long value = 0;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }
}

