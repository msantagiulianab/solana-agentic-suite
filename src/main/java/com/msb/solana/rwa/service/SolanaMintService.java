package com.msb.solana.rwa.service;

import com.msb.solana.rwa.rpc.SolanaRpcException;
import com.msb.solana.rwa.rpc.SolanaRpcAdapter;
import com.msb.solana.rwa.rpc.dto.LatestBlockhash;
import com.msb.solana.rwa.serialization.AccountMeta;
import com.msb.solana.rwa.serialization.Base58Codec;
import com.msb.solana.rwa.serialization.ComputeBudgetInstruction;
import com.msb.solana.rwa.serialization.SolanaInstruction;
import com.msb.solana.rwa.serialization.SolanaKeypair;
import com.msb.solana.rwa.serialization.SolanaKeypairService;
import com.msb.solana.rwa.serialization.SolanaTransactionSerializer;
import com.msb.solana.rwa.serialization.Token2022InstructionBuilder;
import com.msb.solana.rwa.serialization.Token2022Program;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Issues a Token-2022 {@code InitializeMint} transaction with the Permanent
 * Delegate extension on Solana Devnet.
 *
 * <p>Generates a fresh mint account keypair, allocates the extended Token-2022
 * mint (base state + account type + Permanent Delegate TLV), compiles and signs
 * the transaction, then submits it through the {@link SolanaRpcAdapter}. The
 * resulting base58 mint address is returned for persistence against the asset.
 * The Permanent Delegate is set to the enterprise fee-payer wallet, granting
 * regulatory oversight, asset recovery and compliance freeze authority.
 */
@Slf4j
@Service
public class SolanaMintService {

    /**
     * Legacy SPL Token program id. Retained for wire-format tests and legacy
     * tooling; asset issuance now targets {@link #TOKEN_2022_PROGRAM_ID}.
     *
     * @deprecated Token-2022 supersedes the legacy token program.
     */
    @Deprecated
    public static final String TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";

    /**
     * Token-2022 program id — the target for all asset issuance.
     */
    public static final String TOKEN_2022_PROGRAM_ID = Token2022Program.TOKEN_2022_PROGRAM_ID;

    /**
     * System program id, required by the CreateAccount instruction.
     */
    public static final String SYSTEM_PROGRAM_ID = "11111111111111111111111111111111";

    /**
     * Rent sysvar account, required by InitializeMint.
     */
    public static final String RENT_SYSVAR_ID = "SysvarRent111111111111111111111111111111111";

    /**
     * Decimals for minted real-world asset tokens.
     */
    public static final int RWA_TOKEN_DECIMALS = 6;

    private static final int CREATE_ACCOUNT_DISCRIMINATOR = 0;

    /**
     * Space (bytes) required by a Token-2022 mint carrying the Permanent
     * Delegate extension (165 base + 1 account type + 36 TLV entry).
     */
    public static final int TOKEN_2022_MINT_SPACE = Token2022Program.permanentDelegateMintSize();

    /**
     * Explicit compute-unit cap for the mint transaction.
     */
    public static final int DEFAULT_COMPUTE_UNIT_LIMIT = 10_000;

    private final SolanaRpcAdapter rpcAdapter;
    private final SolanaKeypairService keypairService;
    private final SolanaTransactionSerializer transactionSerializer;

    public SolanaMintService(SolanaRpcAdapter rpcAdapter,
                             SolanaKeypairService keypairService,
                             SolanaTransactionSerializer transactionSerializer) {
        this.rpcAdapter = rpcAdapter;
        this.keypairService = keypairService;
        this.transactionSerializer = transactionSerializer;
    }

    /**
     * Creates a Token-2022 mint account carrying the Permanent Delegate
     * extension on Solana Devnet.
     *
     * @return base58 mint address of the newly created token mint
     * @throws SolanaRpcException when the Devnet RPC layer fails
     */
    public String createMint() {
        try {
            SolanaKeypair payer = keypairService.resolveKeypair();
            SolanaKeypair mint = keypairService.generateKeypair();

            byte[] mintPubkey = mint.getPublicKeyBytes();
            byte[] payerPubkey = payer.getPublicKeyBytes();
            byte[] tokenProgram = Token2022InstructionBuilder.programId();

            long rentExemption = rpcAdapter.getMinimumBalanceForRentExemption(TOKEN_2022_MINT_SPACE);

            // Price the transaction dynamically from the node's recent fee samples.
            // Both the fee payer and the freshly-generated mint account are passed
            // as writable-lock filters; the adapter falls back to the configured
            // baseline when the fee oracle is unavailable.
            long priorityFee = rpcAdapter.getRecentPrioritizationFees(
                    List.of(payer.getPublicKeyBase58(), mint.getPublicKeyBase58()));

            // Instruction 0: ComputeBudget.setComputeUnitPrice — set the dynamic
            // priority fee (micro-lamports per compute unit).
            SolanaInstruction setComputeUnitPrice =
                    ComputeBudgetInstruction.setComputeUnitPrice(priorityFee);

            // Instruction 1: ComputeBudget.setComputeUnitLimit — cap compute units
            // so the transaction never consumes more than the mint workflow needs.
            SolanaInstruction setComputeUnitLimit =
                    ComputeBudgetInstruction.setComputeUnitLimit(DEFAULT_COMPUTE_UNIT_LIMIT);

            // Instruction 2: SystemProgram.createAccount — allocate+assign the
            // rent-exempt extended mint account owned by the Token-2022 program.
            SolanaInstruction createAccount = new SolanaInstruction(
                    Base58Codec.decode(SYSTEM_PROGRAM_ID),
                    List.of(
                            new AccountMeta(payerPubkey, true, true),
                            new AccountMeta(mintPubkey, true, true)),
                    buildCreateAccountData(rentExemption, TOKEN_2022_MINT_SPACE, tokenProgram));

            // Instruction 3: Token-2022 InitializePermanentDelegate — attach the
            // enterprise oversight wallet as the mint-level permanent delegate.
            // Token-2022 requires extension initializers to run against the
            // uninitialized mint BEFORE InitializeMint, so the delegate TLV entry
            // is written first.
            SolanaInstruction initializePermanentDelegate =
                    Token2022InstructionBuilder.initializePermanentDelegate(mintPubkey, payerPubkey);

            // Instruction 4: Token-2022 InitializeMint — initialize the freshly
            // created account as a Token-2022 mint (same wire layout as legacy).
            // Runs last because Token-2022 rejects InitializeMint while a required
            // extension (e.g. Permanent Delegate) is still uninitialized.
            SolanaInstruction initializeMint = Token2022InstructionBuilder.initializeMint(
                    mintPubkey, RWA_TOKEN_DECIMALS, payerPubkey, null);

            return submitWithBlockhashRetry(
                    List.of(setComputeUnitPrice, setComputeUnitLimit, createAccount,
                            initializePermanentDelegate, initializeMint),
                    List.of(payer, mint), mint.getPublicKeyBase58());
        } catch (Exception ex) {
            log.error("Failed to create Token-2022 mint on Devnet", ex);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solana Devnet Mint Error: " + ex.getMessage(), ex);
        }
    }

    /**
     * Serializes, signs, and submits the mint creation transaction, retrying up
     * to three attempts when the node reports that the recent blockhash has
     * expired ("Blockhash not found"). Each attempt fetches a fresh blockhash
     * immediately before signing so the transaction is never bound to a stale
     * blockhash.
     */
    private String submitWithBlockhashRetry(List<SolanaInstruction> instructions,
                                            List<SolanaKeypair> signers,
                                            String mintAddress) {
        SolanaRpcException lastBlockhashFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                LatestBlockhash latest = rpcAdapter.getLatestBlockhash();
                String signedTransaction = transactionSerializer.serializeAndSign(
                        instructions,
                        latest.blockhash(),
                        signers);

                rpcAdapter.sendTransaction(signedTransaction);
                return mintAddress;
            } catch (SolanaRpcException ex) {
                if (isBlockhashNotFound(ex)) {
                    lastBlockhashFailure = ex;
                    log.warn("Stale blockhash on attempt {}/3 for SPL mint creation; "
                            + "fetching a fresh blockhash and retrying", attempt);
                    continue;
                }
                throw ex;
            }
        }
        log.error("Exhausted blockhash retries while creating Token-2022 mint", lastBlockhashFailure);
        throw lastBlockhashFailure;
    }

    private boolean isBlockhashNotFound(SolanaRpcException ex) {
        String message = ex.getMessage();
        return message != null && message.toLowerCase().contains("blockhash not found");
    }

    private byte[] buildCreateAccountData(long lamports, long space, byte[] ownerProgramId) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(CREATE_ACCOUNT_DISCRIMINATOR); // u32 (4 bytes) little-endian
        data.write(0);
        data.write(0);
        data.write(0);
        writeU64(data, lamports);   // u64 lamports
        writeU64(data, space);      // u64 space
        data.writeBytes(ownerProgramId); // [32]byte owner
        return data.toByteArray();
    }

    private void writeU64(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 8; i++) {
            out.write((int) (value & 0xFF));
            value >>= 8;
        }
    }
}