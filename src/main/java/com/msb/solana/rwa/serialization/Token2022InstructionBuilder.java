package com.msb.solana.rwa.serialization;

import com.msb.solana.rwa.service.SolanaMintService;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles Token-2022 instructions into the internal {@link SolanaInstruction}
 * wire model consumed by {@link SolanaTransactionSerializer}.
 *
 * <p>Every instruction targets the Token-2022 program id and encodes its data
 * payload exactly as {@code spl-token-2022} does on-chain:
 * <ul>
 *   <li>{@code InitializeMint} — discriminator 0 (identical layout to legacy).</li>
 *   <li>{@code InitializePermanentDelegate} — discriminator 35 + 32-byte delegate.</li>
 *   <li>{@code InitializeTransferHook} — discriminator 36 + sub-discriminator 0
 *       + 32-byte authority + 32-byte hook program id.</li>
 *   <li>{@code TransferChecked} — discriminator 12 + u64 amount + u8 decimals.</li>
 * </ul>
 */
public final class Token2022InstructionBuilder {

    private Token2022InstructionBuilder() {
    }

    /** @return the 32-byte Token-2022 program id */
    public static byte[] programId() {
        return Base58Codec.decode(Token2022Program.TOKEN_2022_PROGRAM_ID);
    }

    /**
     * Builds the Token-2022 {@code InitializeMint} instruction.
     *
     * @param mint            mint account pubkey (writable, non-signer)
     * @param decimals        token decimals
     * @param mintAuthority   mint authority pubkey
     * @param freezeAuthority freeze authority pubkey, or {@code null} for none
     */
    public static SolanaInstruction initializeMint(byte[] mint, int decimals,
                                                   byte[] mintAuthority, byte[] freezeAuthority) {
        return new SolanaInstruction(
                programId(),
                List.of(
                        new AccountMeta(mint, false, true),
                        new AccountMeta(Base58Codec.decode(SolanaMintService.RENT_SYSVAR_ID), false, false)),
                buildInitializeMintData(decimals, mintAuthority, freezeAuthority));
    }

    /**
     * Builds the Token-2022 {@code InitializePermanentDelegate} instruction.
     *
     * @param mint     mint account pubkey (writable, non-signer)
     * @param delegate 32-byte permanent delegate pubkey (enterprise oversight wallet)
     */
    public static SolanaInstruction initializePermanentDelegate(byte[] mint, byte[] delegate) {
        return new SolanaInstruction(
                programId(),
                List.of(new AccountMeta(mint, false, true)),
                buildInitializePermanentDelegateData(delegate));
    }

    /**
     * Builds the Token-2022 {@code InitializeTransferHook} instruction.
     *
     * @param mint          mint account pubkey (writable, non-signer)
     * @param authority     32-byte transfer hook authority (zeroed for none)
     * @param hookProgramId 32-byte transfer hook program id
     */
    public static SolanaInstruction initializeTransferHook(byte[] mint, byte[] authority,
                                                           byte[] hookProgramId) {
        return new SolanaInstruction(
                programId(),
                List.of(new AccountMeta(mint, false, true)),
                buildInitializeTransferHookData(authority, hookProgramId));
    }

    /**
     * Builds the Token-2022 {@code TransferChecked} instruction and appends the
     * transfer hook account metas required by the validator.
     *
     * @param source        source token account (writable)
     * @param mint          mint pubkey (readonly)
     * @param destination   destination token account (writable)
     * @param authority     owner authority of the source account (signer)
     * @param amount        transfer amount in base units
     * @param decimals      token decimals
     * @param extraAccounts transfer hook validation account and any hook data accounts
     */
    public static SolanaInstruction transferChecked(byte[] source, byte[] mint, byte[] destination,
                                                    byte[] authority, long amount, int decimals,
                                                    List<AccountMeta> extraAccounts) {
        List<AccountMeta> accounts = new ArrayList<>(4 + extraAccounts.size());
        accounts.add(new AccountMeta(source, false, true));
        accounts.add(new AccountMeta(mint, false, false));
        accounts.add(new AccountMeta(destination, false, true));
        accounts.add(new AccountMeta(authority, true, false));
        accounts.addAll(extraAccounts);
        return new SolanaInstruction(programId(), accounts, buildTransferCheckedData(amount, decimals));
    }

    // ---------------------------------------------------------------------
    // Instruction data encoders (byte-for-byte with spl-token-2022)
    // ---------------------------------------------------------------------

    public static byte[] buildInitializeMintData(int decimals, byte[] mintAuthority,
                                                 byte[] freezeAuthority) {
        requireLength(mintAuthority, 32, "mint authority");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(Token2022Program.INITIALIZE_MINT_DISCRIMINATOR);
        data.write(decimals & 0xFF);
        data.writeBytes(mintAuthority);
        // COption<Pubkey>: 0 = None, 1 = Some followed by the 32-byte authority.
        data.write(freezeAuthority == null ? 0 : 1);
        if (freezeAuthority != null) {
            requireLength(freezeAuthority, 32, "freeze authority");
            data.writeBytes(freezeAuthority);
        }
        return data.toByteArray();
    }

    public static byte[] buildInitializePermanentDelegateData(byte[] delegate) {
        requireLength(delegate, 32, "permanent delegate");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(Token2022Program.INITIALIZE_PERMANENT_DELEGATE_DISCRIMINATOR);
        data.writeBytes(delegate);
        return data.toByteArray();
    }

    public static byte[] buildInitializeTransferHookData(byte[] authority, byte[] hookProgramId) {
        requireLength(authority, 32, "transfer hook authority");
        requireLength(hookProgramId, 32, "transfer hook program id");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(Token2022Program.TRANSFER_HOOK_EXTENSION_DISCRIMINATOR);
        data.write(Token2022Program.TRANSFER_HOOK_INITIALIZE_DISCRIMINATOR);
        // MaybeNull<Address>: the all-zero 32-byte key denotes None.
        data.writeBytes(authority);
        data.writeBytes(hookProgramId);
        return data.toByteArray();
    }

    public static byte[] buildTransferCheckedData(long amount, int decimals) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(Token2022Program.TRANSFER_CHECKED_DISCRIMINATOR);
        writeU64(data, amount);
        data.write(decimals & 0xFF);
        return data.toByteArray();
    }

    private static void requireLength(byte[] value, int expected, String field) {
        if (value == null || value.length != expected) {
            throw new IllegalArgumentException(field + " must be " + expected + " bytes");
        }
    }

    private static void writeU64(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) (value & 0xFF));
            value >>= 8;
        }
    }
}
