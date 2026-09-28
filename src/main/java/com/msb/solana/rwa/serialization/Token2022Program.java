package com.msb.solana.rwa.serialization;

import java.nio.charset.StandardCharsets;

/**
 * Canonical constants and account-size math for the Solana Token-2022 program
 * ({@code TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb}).
 *
 * <p>Token-2022 stores extensions in a Type-Length-Value (TLV) area appended to
 * the legacy base state. An extended mint is laid out as:
 *
 * <pre>
 *   [0 .. 82)              Mint base state (82 bytes)
 *   [82 .. 83)             padding byte (base state is padded to 83 bytes)
 *   [83]                   AccountType (1 byte, Mint = 1)
 *   [84 ..)                TLV entries: u16 type + u16 length + value
 * </pre>
 *
 * <p>The total account length is therefore {@code 165} base bytes (the largest
 * base state, {@code Account::LEN}) + {@code 1} account-type byte + one TLV
 * entry per extension ({@code 2 + 2 + valueLength}). This class centralizes
 * those canonical lengths and the instruction discriminators so the off-chain
 * instruction builder never hardcodes wire-format magic numbers.
 */
public final class Token2022Program {

    /** Token-2022 program id (identical across all Solana clusters). */
    public static final String TOKEN_2022_PROGRAM_ID =
            "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";

    // ---------------------------------------------------------------------
    // Token-2022 account layout (base state + account type + TLV extensions)
    // ---------------------------------------------------------------------
    /** {@code Account::LEN} — every base state (Mint/Account/Multisig) pads to this. */
    public static final int BASE_ACCOUNT_LENGTH = 165;
    /** Single byte marking the account type (Mint = 1, Account = 2). */
    public static final int ACCOUNT_TYPE_LENGTH = 1;
    /** {@code u16} little-endian extension-type discriminator. */
    public static final int EXTENSION_TYPE_LENGTH = 2;
    /** {@code u16} little-endian TLV value length prefix. */
    public static final int LENGTH_PREFIX_LENGTH = 2;
    /** AccountType ordinal for an extended Mint. */
    public static final int ACCOUNT_TYPE_MINT = 1;

    // ---------------------------------------------------------------------
    // ExtensionType discriminators (u16 little-endian, mint-level)
    // ---------------------------------------------------------------------
    public static final int EXTENSION_PERMANENT_DELEGATE = 12;
    public static final int EXTENSION_TRANSFER_HOOK = 14;

    /**
     * Extension value lengths. Token-2022 models optional authorities with
     * {@code MaybeNull<Address>}: a 32-byte pubkey where the all-zero key
     * denotes {@code None} (no separate tag byte is stored on-chain).
     */
    public static final int PERMANENT_DELEGATE_VALUE_LENGTH = 32;
    /** TransferHook stores two {@code MaybeNull<Address>} fields: authority + program_id. */
    public static final int TRANSFER_HOOK_VALUE_LENGTH = 64;

    // ---------------------------------------------------------------------
    // Instruction discriminators
    // ---------------------------------------------------------------------
    public static final int INITIALIZE_MINT_DISCRIMINATOR = 0;
    public static final int TRANSFER_CHECKED_DISCRIMINATOR = 12;
    public static final int INITIALIZE_PERMANENT_DELEGATE_DISCRIMINATOR = 35;
    public static final int TRANSFER_HOOK_EXTENSION_DISCRIMINATOR = 36;
    public static final int TRANSFER_HOOK_INITIALIZE_DISCRIMINATOR = 0;

    // ---------------------------------------------------------------------
    // Transfer hook PDA seeds
    // ---------------------------------------------------------------------
    /** Seed used to derive the transfer hook {@code extra-account-metas} PDA. */
    public static final byte[] EXTRA_ACCOUNT_METAS_SEED =
            "extra-account-metas".getBytes(StandardCharsets.US_ASCII);

    private Token2022Program() {
    }

    /**
     * Calculates the on-chain data length for an extended Token-2022 mint.
     *
     * @param extensionValueLengths value payload length of each extension
     * @return total account space in bytes
     */
    public static int mintAccountSize(int... extensionValueLengths) {
        int tlvBytes = 0;
        for (int valueLength : extensionValueLengths) {
            if (valueLength < 0) {
                throw new IllegalArgumentException("Extension value length must be non-negative");
            }
            tlvBytes += EXTENSION_TYPE_LENGTH + LENGTH_PREFIX_LENGTH + valueLength;
        }
        return BASE_ACCOUNT_LENGTH + ACCOUNT_TYPE_LENGTH + tlvBytes;
    }

    /** Space required by a mint carrying only the Permanent Delegate extension (202 bytes). */
    public static int permanentDelegateMintSize() {
        return mintAccountSize(PERMANENT_DELEGATE_VALUE_LENGTH);
    }

    /** Space required by a mint carrying only the Transfer Hook extension (234 bytes). */
    public static int transferHookMintSize() {
        return mintAccountSize(TRANSFER_HOOK_VALUE_LENGTH);
    }

    /** Space required by a mint carrying Permanent Delegate + Transfer Hook (270 bytes). */
    public static int permanentDelegateAndTransferHookMintSize() {
        return mintAccountSize(PERMANENT_DELEGATE_VALUE_LENGTH, TRANSFER_HOOK_VALUE_LENGTH);
    }
}
