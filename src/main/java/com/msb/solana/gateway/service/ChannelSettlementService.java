package com.msb.solana.gateway.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
import com.msb.solana.gateway.exception.SettlementConflictException;
import com.msb.solana.gateway.model.SettlementResult;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.gateway.rpc.SolanaRpcClient;
import com.msb.solana.gateway.serialization.Base58;
import com.msb.solana.gateway.serialization.SolanaInstruction;
import com.msb.solana.gateway.serialization.SolanaKeypair;
import com.msb.solana.gateway.serialization.SolanaKeypairService;
import com.msb.solana.gateway.serialization.SolanaWireTransactionBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Settles a payment channel by sweeping its cumulative verified amount to the
 * gateway treasury address on-chain.
 *
 * <p>The settlement source is the gateway settlement wallet derived from
 * {@code solana.gateway.mock-private-key}; in the enterprise deployment this is
 * the channel escrow controlled by the gateway. The destination is
 * {@code solana.gateway.treasury-pubkey}. The sweep transaction is serialized
 * and signed in-process (zero external Web3 dependencies), then broadcast via
 * {@link SolanaRpcClient#sendTransaction(String)}.
 *
 * <p>Settlement runs in three committed phases so a database transaction is never
 * held open across an external Solana RPC call (see {@link #settleChannel}).
 */
@Service
public class ChannelSettlementService {

    private static final Logger log = LoggerFactory.getLogger(ChannelSettlementService.class);

    private final PaymentAuditRepository auditRepository;
    private final SolanaRpcClient rpcClient;
    private final SolanaWireTransactionBuilder transactionBuilder;
    private final SolanaKeypairService keypairService;
    private final TransactionTemplate transactionTemplate;
    private final String treasuryPubkey;
    private final String mockPrivateKey;

    public ChannelSettlementService(PaymentAuditRepository auditRepository,
                                    SolanaRpcClient rpcClient,
                                    SolanaWireTransactionBuilder transactionBuilder,
                                    SolanaKeypairService keypairService,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${solana.gateway.treasury-pubkey:4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y}")
                                    String treasuryPubkey,
                                    @Value("${solana.gateway.mock-private-key:4wBqpZM9xaSheZzJSMawUKKwhdpChKbZ5eu5ky4Vigw}")
                                    String mockPrivateKey) {
        this.auditRepository = auditRepository;
        this.rpcClient = rpcClient;
        this.transactionBuilder = transactionBuilder;
        this.keypairService = keypairService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.treasuryPubkey = treasuryPubkey;
        this.mockPrivateKey = mockPrivateKey;
    }

    /**
     * Sweeps the highest-nonce verified record for the channel to the treasury.
     *
     * <p>Settlement is split into three phases so that no database transaction is
     * ever held open across an external Solana RPC call:
     * <ol>
     *   <li><strong>Claim (committed DB tx):</strong> the record is atomically
     *       transitioned {@code VERIFIED -> SETTLING} with a conditional update,
     *       then the transaction commits immediately — releasing the HikariCP
     *       connection and the row lock before any network I/O. At most one
     *       concurrent sweep can win this claim.</li>
     *   <li><strong>Broadcast (no DB tx):</strong> the recent blockhash is fetched
     *       and the signed sweep transaction is submitted to the node.</li>
     *   <li><strong>Complete (committed DB tx):</strong> on success the record is
     *       transitioned {@code SETTLING -> SETTLED} with the transaction
     *       signature. On an ambiguous broadcast failure the record is moved to a
     *       terminal {@code SETTLEMENT_FAILED} state instead of reverting to
     *       {@code VERIFIED}, so a retry can never double-spend without manual
     *       reconciliation.</li>
     * </ol>
     *
     * @param channelId x402 payment channel identifier
     * @return settlement result with the broadcast transaction signature
     */
    public SettlementResult settleChannel(String channelId) {
        PaymentAuditRecord record = auditRepository.findTopByChannelIdOrderByNonceDesc(channelId)
                .orElseThrow(() -> new IllegalArgumentException("No audit record found for channel: " + channelId));

        if (record.getStatus() != PaymentAuditStatus.VERIFIED) {
            throw new SettlementConflictException("Channel has no unsettled VERIFIED funds: " + channelId);
        }

        Long recordId = record.getId();
        long amount = record.getCumulativeAmountAtomic();

        // Phase A — claim the record in its own committed transaction. The row lock
        // and HikariCP connection are released before any external RPC call.
        Integer claimed = transactionTemplate.execute(status -> auditRepository.claimForSettlement(
                recordId, PaymentAuditStatus.VERIFIED, PaymentAuditStatus.SETTLING));
        if (claimed == null || claimed != 1) {
            throw new SettlementConflictException(
                    "Channel settlement is already in progress or completed: " + channelId);
        }

        // Phase B — external network calls with no open database transaction.
        String blockhash = rpcClient.getLatestBlockhash().orElse(null);
        if (blockhash == null) {
            // No broadcast was attempted, so it is safe to release the claim and
            // leave the channel retryable.
            transactionTemplate.executeWithoutResult(status -> auditRepository.releaseSettlementClaim(
                    recordId, PaymentAuditStatus.SETTLING, PaymentAuditStatus.VERIFIED));
            throw new IllegalStateException("Unable to fetch a recent blockhash for sweep");
        }

        SolanaKeypair payer = keypairService.fromBase58SecretKey(mockPrivateKey);
        byte[] source = payer.getPublicKeyBytes();
        byte[] treasury = Base58.decode(treasuryPubkey);

        SolanaInstruction transfer = SolanaWireTransactionBuilder.systemTransfer(source, treasury, amount);
        String wireTransaction = transactionBuilder.serializeAndSign(
                List.of(transfer), blockhash, List.of(payer));

        String txSignature;
        try {
            txSignature = rpcClient.sendTransaction(wireTransaction)
                    .orElseThrow(() -> new IllegalStateException(
                            "Solana sendTransaction failed for channel: " + channelId));
        } catch (RuntimeException ex) {
            // Ambiguous outcome: the node may have accepted the sweep before the
            // response was lost. Land in a terminal SETTLEMENT_FAILED state so a
            // retry can never double-spend; manual reconciliation is required.
            transactionTemplate.executeWithoutResult(status -> auditRepository.markSettlementFailed(
                    recordId, PaymentAuditStatus.SETTLING, PaymentAuditStatus.SETTLEMENT_FAILED));
            throw ex;
        }

        // Phase C — persist the on-chain signature in its own committed transaction.
        Integer completed = transactionTemplate.execute(status -> auditRepository.completeSettlement(
                recordId, PaymentAuditStatus.SETTLING, PaymentAuditStatus.SETTLED, txSignature));
        if (completed == null || completed != 1) {
            throw new IllegalStateException("Settlement claim was lost before completion for channel: " + channelId);
        }

        log.info("Channel settlement sweep broadcast: channel={} amountAtomic={} txSignature={}",
                channelId, amount, txSignature);

        return new SettlementResult(channelId, amount, txSignature, System.currentTimeMillis());
    }
}
