package com.msb.solana.gateway.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.entity.PaymentAuditStatus;
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
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class ChannelSettlementService {

    private static final Logger log = LoggerFactory.getLogger(ChannelSettlementService.class);

    private final PaymentAuditRepository auditRepository;
    private final SolanaRpcClient rpcClient;
    private final SolanaWireTransactionBuilder transactionBuilder;
    private final SolanaKeypairService keypairService;
    private final String treasuryPubkey;
    private final String mockPrivateKey;

    public ChannelSettlementService(PaymentAuditRepository auditRepository,
                                    SolanaRpcClient rpcClient,
                                    SolanaWireTransactionBuilder transactionBuilder,
                                    SolanaKeypairService keypairService,
                                    @Value("${solana.gateway.treasury-pubkey:4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y}")
                                    String treasuryPubkey,
                                    @Value("${solana.gateway.mock-private-key:4wBqpZM9xaSheZzJSMawUKKwhdpChKbZ5eu5ky4Vigw}")
                                    String mockPrivateKey) {
        this.auditRepository = auditRepository;
        this.rpcClient = rpcClient;
        this.transactionBuilder = transactionBuilder;
        this.keypairService = keypairService;
        this.treasuryPubkey = treasuryPubkey;
        this.mockPrivateKey = mockPrivateKey;
    }

    /**
     * Sweeps the highest-nonce verified record for the channel to the treasury.
     *
     * @param channelId x402 payment channel identifier
     * @return settlement result with the broadcast transaction signature
     */
    @Transactional
    public SettlementResult settleChannel(String channelId) {
        PaymentAuditRecord record = auditRepository.findTopByChannelIdOrderByNonceDesc(channelId)
                .orElseThrow(() -> new IllegalArgumentException("No audit record found for channel: " + channelId));

        if (record.getStatus() != PaymentAuditStatus.VERIFIED) {
            throw new IllegalStateException("Channel has no unsettled VERIFIED funds: " + channelId);
        }

        String blockhash = rpcClient.getLatestBlockhash()
                .orElseThrow(() -> new IllegalStateException("Unable to fetch a recent blockhash for sweep"));

        SolanaKeypair payer = keypairService.fromBase58SecretKey(mockPrivateKey);
        byte[] source = payer.getPublicKeyBytes();
        byte[] treasury = Base58.decode(treasuryPubkey);
        long amount = record.getCumulativeAmountAtomic();

        SolanaInstruction transfer = SolanaWireTransactionBuilder.systemTransfer(source, treasury, amount);
        String wireTransaction = transactionBuilder.serializeAndSign(
                List.of(transfer), blockhash, List.of(payer));

        String txSignature = rpcClient.sendTransaction(wireTransaction)
                .orElseThrow(() -> new IllegalStateException("Solana sendTransaction failed for channel: " + channelId));

        record.markSettled(txSignature);
        auditRepository.save(record);

        log.info("Channel settlement sweep broadcast: channel={} amountAtomic={} txSignature={}",
                channelId, amount, txSignature);

        return new SettlementResult(channelId, amount, txSignature, System.currentTimeMillis());
    }
}
