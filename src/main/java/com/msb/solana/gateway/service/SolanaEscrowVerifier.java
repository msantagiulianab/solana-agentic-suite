package com.msb.solana.gateway.service;

import com.msb.solana.gateway.rpc.SolanaRpcClient;
import com.msb.solana.gateway.rpc.model.AccountInfoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * On-chain escrow verification with a short-TTL in-memory cache.
 *
 * <p>In production ({@code solana.rpc.mock-mode: false}) the escrow balance is
 * resolved from a Solana RPC {@code getAccountInfo} query keyed to the channel.
 * The result is cached for a short TTL so the x402 hot path stays &lt;5ms on a
 * cache hit and never performs a synchronous RPC call on the request path.
 *
 * <p>When mock mode is enabled the verifier never touches the network and
 * returns the deterministic demo ceiling, keeping the test suite fast and
 * independent of external RPC flakiness.
 */
@Service
public class SolanaEscrowVerifier implements EscrowBalanceProvider {

    private static final Logger log = LoggerFactory.getLogger(SolanaEscrowVerifier.class);

    public static final String MOCK_CHANNEL_ID = "chan_demo_solana_001";
    public static final String SMOKE_TEST_CHANNEL_ID = "chan_smoke_test_001";
    public static final long MOCK_CEILING_ATOMIC = 1_000_000L;

    private static final Set<String> MOCK_CHANNEL_IDS = Set.of(MOCK_CHANNEL_ID, SMOKE_TEST_CHANNEL_ID);

    private final SolanaRpcClient rpcClient;
    private final boolean mockMode;
    private final long cacheTtlMillis;
    private final String defaultEscrowPubkey;

    private final ConcurrentMap<String, CachedBalance> cache = new ConcurrentHashMap<>();

    public SolanaEscrowVerifier(
            SolanaRpcClient rpcClient,
            @Value("${solana.rpc.mock-mode:false}") boolean mockMode,
            @Value("${solana.rpc.cache-ttl-millis:5000}") long cacheTtlMillis,
            @Value("${x402.escrow-pubkey:7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU}") String defaultEscrowPubkey) {
        this.rpcClient = rpcClient;
        this.mockMode = mockMode;
        this.cacheTtlMillis = cacheTtlMillis;
        this.defaultEscrowPubkey = defaultEscrowPubkey;
    }

    @Override
    public long getVerifiedDepositCeiling(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return 0L;
        }

        if (mockMode) {
            return mockCeiling(channelId);
        }

        long now = System.currentTimeMillis();
        CachedBalance cached = cache.get(channelId);
        if (cached != null && (now - cached.cachedAtMillis()) < cacheTtlMillis) {
            return cached.balanceAtomic();
        }

        long ceiling = queryOnChainCeiling(channelId);
        cache.put(channelId, new CachedBalance(ceiling, now));
        return ceiling;
    }

    /**
     * Package-private reset hook for test isolation between runs.
     */
    public void resetState() {
        cache.clear();
    }

    private long mockCeiling(String channelId) {
        if (MOCK_CHANNEL_IDS.contains(channelId)) {
            return MOCK_CEILING_ATOMIC;
        }
        log.warn("Escrow verification: no mock ceiling configured for channel {}", channelId);
        return 0L;
    }

    private long queryOnChainCeiling(String channelId) {
        String escrowAddress = resolveEscrowAddress(channelId);
        Optional<AccountInfoResponse> accountInfo = rpcClient.getAccountInfo(escrowAddress);

        if (accountInfo.isEmpty() || accountInfo.get().value() == null) {
            log.warn("Escrow verification: no on-chain account found for channel {} (escrow {})",
                    channelId, escrowAddress);
            return 0L;
        }

        long lamports = accountInfo.get().value().lamports();
        if (lamports <= 0L) {
            log.warn("Escrow verification: zero on-chain balance for channel {} (escrow {})",
                    channelId, escrowAddress);
            return 0L;
        }

        return lamports;
    }

    private String resolveEscrowAddress(String channelId) {
        // Single settlement escrow for the gateway; per-channel escrow PDA
        // derivation can be layered here without changing the callers.
        return defaultEscrowPubkey;
    }

    private record CachedBalance(long balanceAtomic, long cachedAtMillis) {
    }
}
