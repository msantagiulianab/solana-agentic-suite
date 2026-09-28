package com.msb.solana.gateway.serialization;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a list of {@link SolanaInstruction}s into a Solana transaction
 * message, signs it with the supplied keypairs, and returns the base64-encoded
 * signed transaction suitable for the {@code sendTransaction} JSON-RPC method.
 *
 * <p>Implements the legacy (non-versioned) transaction wire format: header,
 * compacted account list, recent blockhash, and instructions. No live node
 * interaction occurs here; signing is performed in-process with
 * {@link SolanaKeypairService}. Zero external Solana Web3 dependencies are used.
 */
@Component
public class SolanaWireTransactionBuilder {

    /** Native System program id (identical across all Solana clusters). */
    public static final String SYSTEM_PROGRAM_ID = "11111111111111111111111111111111";

    /** Legacy SPL Token program id. */
    public static final String TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";

    private static final int SYSTEM_TRANSFER_DISCRIMINATOR = 2;
    private static final int TOKEN_TRANSFER_DISCRIMINATOR = 3;

    private final SolanaKeypairService keypairService;

    public SolanaWireTransactionBuilder(SolanaKeypairService keypairService) {
        this.keypairService = keypairService;
    }

    /**
     * Serializes and signs the given instructions.
     *
     * @param instructions    instructions to include (program ids are compiled as
     *                        readonly accounts automatically)
     * @param recentBlockhash base58 recent blockhash (decoded to 32 bytes)
     * @param signers         keypairs that must sign the transaction (the first
     *                        signer is the fee payer and is always included as a
     *                        writable signer account in the compiled message)
     * @return base64-encoded signed transaction bytes
     */
    public String serializeAndSign(List<SolanaInstruction> instructions,
                                   String recentBlockhash,
                                   List<SolanaKeypair> signers) {
        if (instructions == null || instructions.isEmpty()) {
            throw new IllegalArgumentException("At least one instruction is required");
        }
        if (signers == null || signers.isEmpty()) {
            throw new IllegalArgumentException("At least one signer is required");
        }

        byte[] blockhash = Base58.decode(recentBlockhash);
        if (blockhash.length != 32) {
            throw new IllegalArgumentException("Recent blockhash must decode to 32 bytes");
        }

        CompiledAccounts compiled = compileAccounts(instructions, signers);
        byte[] message = serializeMessage(instructions, compiled, blockhash);

        ByteArrayOutputStream transaction = new ByteArrayOutputStream();
        transaction.writeBytes(CompactU16.encode(signers.size()));
        for (SolanaKeypair signer : signers) {
            byte[] signature = keypairService.sign(message, signer);
            if (signature.length != 64) {
                throw new IllegalStateException("Ed25519 signature must be 64 bytes");
            }
            transaction.writeBytes(signature);
        }
        transaction.writeBytes(message);

        return Base64.getEncoder().encodeToString(transaction.toByteArray());
    }

    /**
     * Builds a native System program {@code Transfer} instruction:
     * {@code 0x02} discriminator followed by an 8-byte little-endian unsigned
     * {@code u64} lamport amount.
     *
     * @param from     source account (writable signer)
     * @param to       destination account (writable)
     * @param lamports amount in lamports
     */
    public static SolanaInstruction systemTransfer(byte[] from, byte[] to, long lamports) {
        if (lamports < 0) {
            throw new IllegalArgumentException("lamports must be non-negative");
        }
        byte[] data = new byte[12];
        writeU32(data, 0, SYSTEM_TRANSFER_DISCRIMINATOR);
        writeU64(data, 4, lamports);
        return new SolanaInstruction(
                Base58.decode(SYSTEM_PROGRAM_ID),
                List.of(
                        new AccountMeta(from, true, true),
                        new AccountMeta(to, false, true)),
                data);
    }

    /**
     * Builds a legacy SPL Token {@code Transfer} instruction:
     * {@code 0x03} discriminator followed by an 8-byte little-endian unsigned
     * {@code u64} amount.
     *
     * @param source      source token account (writable)
     * @param destination destination token account (writable)
     * @param authority   token authority (signer)
     * @param amount      raw token amount (already scaled by decimals)
     */
    public static SolanaInstruction tokenTransfer(byte[] source, byte[] destination,
                                                  byte[] authority, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        byte[] data = new byte[9];
        data[0] = (byte) TOKEN_TRANSFER_DISCRIMINATOR;
        writeU64(data, 1, amount);
        return new SolanaInstruction(
                Base58.decode(TOKEN_PROGRAM_ID),
                List.of(
                        new AccountMeta(source, false, true),
                        new AccountMeta(destination, false, true),
                        new AccountMeta(authority, true, false)),
                data);
    }

    private CompiledAccounts compileAccounts(List<SolanaInstruction> instructions,
                                             List<SolanaKeypair> signers) {
        Map<String, AccountMeta> unique = new LinkedHashMap<>();

        for (SolanaKeypair signer : signers) {
            merge(unique, new AccountMeta(signer.getPublicKeyBytes(), true, true));
        }

        for (SolanaInstruction instruction : instructions) {
            for (AccountMeta meta : instruction.accounts()) {
                merge(unique, meta);
            }
            merge(unique, new AccountMeta(instruction.programId(), false, false));
        }

        List<AccountMeta> ordered = new ArrayList<>(unique.values());
        int requiredSignatures = 0;
        int readonlySigned = 0;
        int readonlyUnsigned = 0;
        for (AccountMeta meta : ordered) {
            if (meta.signer()) {
                requiredSignatures++;
                if (!meta.writable()) {
                    readonlySigned++;
                }
            } else if (!meta.writable()) {
                readonlyUnsigned++;
            }
        }

        Map<String, Integer> indexByKey = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            indexByKey.put(Base58.encode(ordered.get(i).pubkey()), i);
        }

        return new CompiledAccounts(ordered, requiredSignatures, readonlySigned, readonlyUnsigned, indexByKey);
    }

    private void merge(Map<String, AccountMeta> unique, AccountMeta meta) {
        String key = Base58.encode(meta.pubkey());
        AccountMeta existing = unique.get(key);
        if (existing == null) {
            unique.put(key, meta);
        } else {
            unique.put(key, new AccountMeta(
                    existing.pubkey(),
                    existing.signer() || meta.signer(),
                    existing.writable() || meta.writable()));
        }
    }

    private byte[] serializeMessage(List<SolanaInstruction> instructions,
                                    CompiledAccounts compiled,
                                    byte[] blockhash) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(compiled.requiredSignatures());
        out.write(compiled.readonlySigned());
        out.write(compiled.readonlyUnsigned());

        out.writeBytes(CompactU16.encode(compiled.accounts().size()));
        for (AccountMeta meta : compiled.accounts()) {
            out.writeBytes(meta.pubkey());
        }

        out.writeBytes(blockhash);

        out.writeBytes(CompactU16.encode(instructions.size()));
        for (SolanaInstruction instruction : instructions) {
            int programIndex = compiled.indexOf(instruction.programId());
            out.write(programIndex);

            out.writeBytes(CompactU16.encode(instruction.accounts().size()));
            for (AccountMeta meta : instruction.accounts()) {
                out.write(compiled.indexOf(meta.pubkey()));
            }

            byte[] data = instruction.data();
            out.writeBytes(CompactU16.encode(data.length));
            out.writeBytes(data);
        }

        return out.toByteArray();
    }

    private static void writeU32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >> 8) & 0xFF);
        out[offset + 2] = (byte) ((value >> 16) & 0xFF);
        out[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeU64(byte[] out, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            out[offset + i] = (byte) (value & 0xFF);
            value >>= 8;
        }
    }

    private record CompiledAccounts(
            List<AccountMeta> accounts,
            int requiredSignatures,
            int readonlySigned,
            int readonlyUnsigned,
            Map<String, Integer> indexByKey) {

        int indexOf(byte[] pubkey) {
            Integer index = indexByKey.get(Base58.encode(pubkey));
            if (index == null) {
                throw new IllegalStateException("Account not found during instruction compilation");
            }
            return index;
        }
    }
}

