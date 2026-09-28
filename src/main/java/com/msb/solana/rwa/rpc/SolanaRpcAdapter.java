package com.msb.solana.rwa.rpc;

import com.msb.solana.rwa.rpc.dto.AccountInfo;
import com.msb.solana.rwa.rpc.dto.AccountInfoResult;
import com.msb.solana.rwa.rpc.dto.LatestBlockhash;
import com.msb.solana.rwa.rpc.dto.LatestBlockhashResult;
import com.msb.solana.rwa.rpc.dto.PrioritizationFee;
import com.msb.solana.rwa.rpc.dto.RpcEnvelope;
import com.msb.solana.rwa.rpc.dto.SignatureStatus;
import com.msb.solana.rwa.rpc.dto.SignatureStatusResult;
import com.msb.solana.rwa.rpc.dto.SignatureStatusesResult;
import com.msb.solana.rwa.rpc.dto.TokenAccountBalance;
import com.msb.solana.rwa.rpc.dto.TokenAccountBalanceResult;
import com.msb.solana.rwa.rpc.dto.RpcSimulationResponseDto;
import com.msb.solana.rwa.rpc.dto.SimulationRequestDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin, resilient JSON-RPC client for the Solana Devnet node.
 *
 * <p>Reads the endpoint from {@code solana.rpc.url} ({@code SOLANA_DEVNET_RPC_URL}
 * env var). Every RPC interaction is wrapped in try-catch and translated into a
 * {@link SolanaRpcException} so callers can fail closed: a timeout, HTTP error,
 * malformed envelope, or JSON-RPC error payload NEVER silently yields a green
 * compliance decision.
 */
@Slf4j
@Service
public class SolanaRpcAdapter {

    private static final String JSONRPC_VERSION = "2.0";

    /**
     * Fallback rent-exempt minimum balance (lamports) for an 82-byte SPL Token
     * mint account, used when the live node cannot be queried.
     */
    public static final long DEFAULT_MINT_RENT_EXEMPTION = 1_461_600L;

    private final RestClient restClient;
    private final String rpcUrl;
    private final long baselinePriorityFee;
    private final AtomicLong requestId = new AtomicLong(1);

    public SolanaRpcAdapter(RestClient.Builder restClientBuilder,
                            @Value("${solana.rpc.url}") String rpcUrl,
                            @Value("${solana.rpc.priority-fee-baseline-micro-lamports:1000}") long baselinePriorityFee) {
        this.restClient = restClientBuilder.build();
        this.rpcUrl = rpcUrl;
        this.baselinePriorityFee = baselinePriorityFee;
    }

    /**
     * Queries live Devnet state for a wallet address.
     *
     * @param walletAddress base58 wallet public key
     * @return parsed account info; {@code exists()} is {@code false} when the
     *         account has not been created on-chain
     * @throws SolanaRpcException on network failure, HTTP error, or malformed/error response
     */
    public AccountInfo getAccountInfo(String walletAddress) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("jsonrpc", JSONRPC_VERSION);
        params.put("method", "getAccountInfo");
        params.put("id", requestId.getAndIncrement());
        params.put("params", List.of(walletAddress, Map.of("encoding", "base64")));

        RpcEnvelope<AccountInfoResult> envelope = call(
                "getAccountInfo", params, new ParameterizedTypeReference<>() {
                });
        if (envelope.hasError()) {
            throw new SolanaRpcException("Solana RPC call 'getAccountInfo' failed: JSON-RPC error "
                    + envelope.error().code() + " (" + envelope.error().message() + ")");
        }
        return envelope.result().valueOrAbsent();
    }

    /**
     * Queries the current Devnet recent blockhash and last valid block height.
     *
     * @return parsed recent blockhash to be embedded into a transaction message
     * @throws SolanaRpcException on network failure, HTTP error, or malformed/error response
     */
    public LatestBlockhash getLatestBlockhash() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("jsonrpc", JSONRPC_VERSION);
        params.put("method", "getLatestBlockhash");
        params.put("id", requestId.getAndIncrement());
        params.put("params", List.of(Map.of("commitment", "confirmed")));

        RpcEnvelope<LatestBlockhashResult> envelope = call(
                "getLatestBlockhash", params, new ParameterizedTypeReference<>() {
                });
        if (envelope.hasError()) {
            throw new SolanaRpcException("Solana RPC call 'getLatestBlockhash' failed: JSON-RPC error "
                    + envelope.error().code() + " (" + envelope.error().message() + ")");
        }
        if (envelope.result() == null || envelope.result().value() == null) {
            throw new SolanaRpcException("Solana RPC call 'getLatestBlockhash' failed: node returned a null result");
        }
        return envelope.result().value();
    }

    /**
     * Queries the rent-exempt minimum balance (lamports) for an account of a
     * given data length.
     *
     * <p>Unlike other adapters this call does not fail closed on an RPC error:
     * the rent schedule is a network-wide constant, so an unreachable node or a
     * malformed/error response falls back to the default for an 82-byte SPL
     * Token mint ({@link #DEFAULT_MINT_RENT_EXEMPTION}).
     *
     * @param dataLength account space in bytes
     * @return rent-exempt minimum balance in lamports
     */
    public long getMinimumBalanceForRentExemption(long dataLength) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("jsonrpc", JSONRPC_VERSION);
        params.put("method", "getMinimumBalanceForRentExemption");
        params.put("id", requestId.getAndIncrement());
        params.put("params", List.of(dataLength, Map.of("commitment", "confirmed")));

        try {
            RpcEnvelope<Long> envelope = call(
                    "getMinimumBalanceForRentExemption",
                    params,
                    new ParameterizedTypeReference<>() {
                    });
            if (!envelope.hasError() && envelope.result() != null) {
                return envelope.result();
            }
            log.warn("Solana RPC 'getMinimumBalanceForRentExemption' returned no usable value; "
                    + "falling back to {} lamports", DEFAULT_MINT_RENT_EXEMPTION);
        } catch (SolanaRpcException ex) {
            log.warn("Solana RPC 'getMinimumBalanceForRentExemption' unavailable ({}); "
                    + "falling back to {} lamports", ex.getMessage(), DEFAULT_MINT_RENT_EXEMPTION);
        }
        return DEFAULT_MINT_RENT_EXEMPTION;
    }

    /**
     * Submits a fully-signed base64 transaction to the Devnet node.
     *
     * @param base64Tx base64-encoded serialized signed transaction payload
     * @return base58 transaction signature confirmed accepted by the node
     * @throws SolanaRpcException on network failure, HTTP error, or malformed/error response
     */
    public String sendTransaction(String base64Tx) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("jsonrpc", JSONRPC_VERSION);
        params.put("method", "sendTransaction");
        params.put("id", requestId.getAndIncrement());
        params.put("params", List.of(
                base64Tx,
                Map.of("encoding", "base64", "preflightCommitment", "confirmed", "skipPreflight", false)));

        RpcEnvelope<String> envelope = call(
                "sendTransaction", params, new ParameterizedTypeReference<>() {
                });
        if (envelope.hasError()) {
            throw new SolanaRpcException("Solana RPC call 'sendTransaction' failed: JSON-RPC error "
                    + envelope.error().code() + " (" + envelope.error().message() + ")");
        }
        if (envelope.result() == null || envelope.result().isBlank()) {
            throw new SolanaRpcException("Solana RPC call 'sendTransaction' failed: node returned a null signature");
        }
        return envelope.result();
    }

    /**
     * Rehearses a base64-encoded wire transaction against the node without
     * broadcasting it.
     *
     * @param encodedTransaction base64-encoded serialized transaction payload
     * @return the raw simulation outcome (including any execution error)
     * @throws SolanaRpcException on network failure, HTTP error, JSON-RPC error, or null result
     */
    public RpcSimulationResponseDto.SimulationValue simulateTransaction(String encodedTransaction) {
        SimulationRequestDto payload = SimulationRequestDto.of(encodedTransaction, requestId.getAndIncrement());

        RpcEnvelope<RpcSimulationResponseDto> envelope = call(
                "simulateTransaction", payload, new ParameterizedTypeReference<>() {
                });
        if (envelope.hasError()) {
            throw new SolanaRpcException("Solana RPC call 'simulateTransaction' failed: JSON-RPC error "
                    + envelope.error().code() + " (" + envelope.error().message() + ")");
        }
        if (envelope.result() == null || envelope.result().value() == null) {
            throw new SolanaRpcException("Solana RPC call 'simulateTransaction' failed: node returned a null result");
        }
        return envelope.result().value();
    }

    /**
     * Queries live Devnet state for an SPL token account balance.
     *
     * @param tokenAccountAddress base58 token account public key
     * @return parsed token balance (amount, decimals, UI string)
     * @throws SolanaRpcException when the token account does not exist, or on any
     *         network/HTTP/JSON-RPC failure
     */
    public TokenAccountBalance getTokenAccountBalance(String tokenAccountAddress) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("jsonrpc", JSONRPC_VERSION);
        params.put("method", "getTokenAccountBalance");
        params.put("id", requestId.getAndIncrement());
        params.put("params", List.of(tokenAccountAddress));

        RpcEnvelope<TokenAccountBalanceResult> envelope = call(
                "getTokenAccountBalance", params, new ParameterizedTypeReference<>() {
                });
        if (envelope.hasError()) {
            throw new SolanaRpcException("Solana RPC call 'getTokenAccountBalance' failed: JSON-RPC error "
                    + envelope.error().code() + " (" + envelope.error().message() + ")");
        }
        if (envelope.result() == null || envelope.result().value() == null) {
            throw new SolanaRpcException("Solana RPC call 'getTokenAccountBalance' failed: "
                    + "token account " + tokenAccountAddress + " does not exist");
        }
        return envelope.result().value();
    }

    /**
     * Queries recent prioritization-fee samples for the supplied writable accounts
     * and returns the 75th-percentile fee in micro-lamports.
     *
     * <p>The Solana {@code getRecentPrioritizationFees} RPC returns a bare array of
     * {@code {slot, prioritizationFee}} samples, so the envelope result type is
     * {@code List<PrioritizationFee>}. Unlike the fail-closed adapters, this call is
     * fail-safe: a timeout, HTTP/JSON-RPC error, or an empty/null sample set falls
     * back to the configured {@code solana.rpc.priority-fee-baseline-micro-lamports}
     * baseline so a transient fee-oracle outage never blocks mint creation.
     *
     * @param accountKeys base58 addresses whose writable locks filter the fee samples
     * @return 75th-percentile prioritization fee in micro-lamports, or the baseline on failure
     */
    public long getRecentPrioritizationFees(List<String> accountKeys) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("jsonrpc", JSONRPC_VERSION);
        params.put("method", "getRecentPrioritizationFees");
        params.put("id", requestId.getAndIncrement());
        params.put("params", List.of(accountKeys));

        try {
            RpcEnvelope<List<PrioritizationFee>> envelope = call(
                    "getRecentPrioritizationFees", params, new ParameterizedTypeReference<>() {
                    });
            if (envelope.hasError()) {
                log.warn("Solana RPC 'getRecentPrioritizationFees' returned JSON-RPC error {}; "
                        + "falling back to {} micro-lamports", envelope.error().code(), baselinePriorityFee);
                return baselinePriorityFee;
            }

            List<Long> fees = envelope.result() == null
                    ? List.of()
                    : envelope.result().stream()
                            .map(PrioritizationFee::prioritizationFee)
                            .toList();
            long fee = seventyFifthPercentile(fees, baselinePriorityFee);
            if (fees.isEmpty()) {
                log.warn("Solana RPC 'getRecentPrioritizationFees' returned no fee samples; "
                        + "falling back to {} micro-lamports", baselinePriorityFee);
            }
            return fee;
        } catch (SolanaRpcException ex) {
            log.warn("Solana RPC 'getRecentPrioritizationFees' unavailable ({}); "
                    + "falling back to {} micro-lamports", ex.getMessage(), baselinePriorityFee);
            return baselinePriorityFee;
        }
    }

    /**
     * Queries the on-chain commitment status of one or more transaction
     * signatures via {@code getSignatureStatuses}, searching transaction history
     * (not just the node's recent-signature cache).
     *
     * <p>The JSON-RPC request is:
     * <pre>{@code
     * {"jsonrpc":"2.0","id":1,"method":"getSignatureStatuses",
     *  "params":[["<SIG>", ...],{"searchTransactionHistory":true}]}
     * }</pre>
     *
     * <p>Responses are index-aligned with the requested signatures; a missing
     * value is mapped to an unconfirmed result (null status) so callers fail
     * closed rather than assume success. Timeouts, HTTP errors, JSON-RPC error
     * payloads, and null results surface as {@link SolanaRpcException}.
     *
     * @param signatures base58 transaction signatures to inspect
     * @return immutable, index-aligned results — one per requested signature
     * @throws SolanaRpcException on network failure, HTTP error, or malformed/error response
     */
    public List<SignatureStatusResult> getSignatureStatuses(List<String> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            return List.of();
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("jsonrpc", JSONRPC_VERSION);
        params.put("method", "getSignatureStatuses");
        params.put("id", requestId.getAndIncrement());
        params.put("params", List.of(signatures, Map.of("searchTransactionHistory", true)));

        RpcEnvelope<SignatureStatusesResult> envelope = call(
                "getSignatureStatuses", params, new ParameterizedTypeReference<>() {
                });
        if (envelope.hasError()) {
            throw new SolanaRpcException("Solana RPC call 'getSignatureStatuses' failed: JSON-RPC error "
                    + envelope.error().code() + " (" + envelope.error().message() + ")");
        }
        if (envelope.result() == null) {
            throw new SolanaRpcException("Solana RPC call 'getSignatureStatuses' failed: node returned a null result");
        }

        List<SignatureStatus> values = envelope.result().value() == null
                ? List.of()
                : envelope.result().value();
        List<SignatureStatusResult> results = new ArrayList<>(signatures.size());
        for (int i = 0; i < signatures.size(); i++) {
            SignatureStatus status = i < values.size() ? values.get(i) : null;
            results.add(SignatureStatusResult.from(signatures.get(i), status));
        }
        return List.copyOf(results);
    }

    /**
     * Computes the 75th percentile of prioritization fees using the nearest-rank
     * method, returning {@code fallback} when no samples are available.
     *
     * <p>Pure static function with no Spring dependencies, kept package-private so
     * it stays unit-testable in isolation.
     */
    static long seventyFifthPercentile(List<Long> fees, long fallback) {
        if (fees == null || fees.isEmpty()) {
            return fallback;
        }
        List<Long> sorted = fees.stream().sorted().toList();
        int rank = (int) Math.ceil(0.75 * sorted.size());
        return sorted.get(rank - 1);
    }

    private <T> RpcEnvelope<T> call(String method, Object payload,
                                    ParameterizedTypeReference<RpcEnvelope<T>> typeRef) {
        try {
            RpcEnvelope<T> envelope = restClient.post()
                    .uri(rpcUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(typeRef);

            if (envelope == null) {
                throw new SolanaRpcException("Solana RPC call '" + method
                        + "' failed: node returned a null or malformed response");
            }
            return envelope;
        } catch (ResourceAccessException ex) {
            throw new SolanaRpcException(method, "Solana node unreachable or timed out", ex);
        } catch (RestClientResponseException ex) {
            throw new SolanaRpcException(method,
                    "HTTP error from Solana RPC node (status " + ex.getStatusCode().value() + ")", ex);
        }
    }
}
