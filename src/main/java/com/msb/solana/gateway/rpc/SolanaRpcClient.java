package com.msb.solana.gateway.rpc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.rpc.model.AccountInfoResponse;
import com.msb.solana.gateway.rpc.model.LatestBlockhashResponse;
import com.msb.solana.gateway.rpc.model.RpcRequest;
import com.msb.solana.gateway.rpc.model.RpcResponse;
import com.msb.solana.gateway.serialization.Base58;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin, fail-closed JSON-RPC 2.0 client for a Solana RPC node.
 *
 * <p>Uses the JDK's standard {@link HttpClient} and Jackson for serialization so
 * no third-party Web3 runtime dependency is introduced. Network failures, HTTP
 * error statuses, JSON-RPC error payloads, and absent on-chain accounts are all
 * translated into {@link Optional#empty()} so callers never silently grant a
 * payment decision based on an unreadable node.
 */
@Component
public class SolanaRpcClient {

    private static final Logger log = LoggerFactory.getLogger(SolanaRpcClient.class);

    private static final String COMMITMENT_CONFIRMED = "confirmed";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String rpcUrl;
    private final boolean mockMode;
    private final AtomicLong requestId = new AtomicLong(1);

    public SolanaRpcClient(ObjectMapper objectMapper,
                           HttpClient httpClient,
                           @Value("${solana.rpc.url:https://api.devnet.solana.com}") String rpcUrl,
                           @Value("${solana.rpc.mock-mode:false}") boolean mockMode) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.rpcUrl = rpcUrl;
        this.mockMode = mockMode;
    }

    /**
     * Queries on-chain account state with base64 encoding at {@code confirmed}
     * commitment.
     *
     * @param pubkeyBase58 base58-encoded account public key
     * @return the parsed account info, or {@link Optional#empty()} when the
     *         account does not exist or the node cannot be queried
     */
    public Optional<AccountInfoResponse> getAccountInfo(String pubkeyBase58) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("encoding", "base64");
        config.put("commitment", COMMITMENT_CONFIRMED);

        RpcResponse<AccountInfoResponse> response = call(
                "getAccountInfo",
                List.of(pubkeyBase58, config),
                new TypeReference<>() {
                });

        if (response == null || response.hasError() || response.result() == null
                || response.result().value() == null) {
            return Optional.empty();
        }
        return Optional.of(response.result());
    }

    /**
     * Queries the most recent blockhash at {@code confirmed} commitment.
     *
     * @return the base58-encoded blockhash, or {@link Optional#empty()} on failure
     */
    public Optional<String> getLatestBlockhash() {
        if (mockMode) {
            return Optional.of(deterministicMockBlockhash());
        }

        RpcResponse<LatestBlockhashResponse> response = call(
                "getLatestBlockhash",
                List.of(Map.of("commitment", COMMITMENT_CONFIRMED)),
                new TypeReference<>() {
                });

        if (response == null || response.hasError() || response.result() == null
                || response.result().value() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(response.result().value().blockhash());
    }

    /**
     * Submits a fully-signed, base64-encoded wire transaction to the node with
     * {@code encoding: "base64"} and {@code preflightCommitment: "confirmed"}.
     *
     * <p>In mock mode no network call is issued; instead a deterministic base58
     * transaction signature is derived from the transaction payload so callers
     * and integration tests observe a stable, replayable signature.
     *
     * @param base64EncodedWireTx base64-encoded serialized signed transaction
     * @return the base58 transaction signature, or {@link Optional#empty()} when
     *         the node returns an error or cannot be reached
     */
    public Optional<String> sendTransaction(String base64EncodedWireTx) {
        if (mockMode) {
            return Optional.of(deterministicMockSignature(base64EncodedWireTx));
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("encoding", "base64");
        config.put("preflightCommitment", COMMITMENT_CONFIRMED);

        RpcResponse<String> response = call(
                "sendTransaction",
                List.of(base64EncodedWireTx, config),
                new TypeReference<>() {
                });

        if (response == null || response.hasError() || response.result() == null
                || response.result().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(response.result());
    }

    private String deterministicMockBlockhash() {
        byte[] digest = sha256("solana-devnet-mock-blockhash".getBytes(StandardCharsets.UTF_8));
        return Base58.encode(digest);
    }

    private String deterministicMockSignature(String base64EncodedWireTx) {
        byte[] txBytes = Base64.getDecoder().decode(base64EncodedWireTx);
        return Base58.encode(sha256(txBytes));
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }

    private <T> T call(String method, Object params, TypeReference<T> typeReference) {
        RpcRequest request = RpcRequest.of(requestId.getAndIncrement(), method, params);
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(rpcUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                log.warn("Solana RPC call '{}' failed: HTTP status {}", method, httpResponse.statusCode());
                return null;
            }
            return objectMapper.readValue(httpResponse.body(), typeReference);
        } catch (IOException e) {
            log.warn("Solana RPC call '{}' failed: {}", method, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Solana RPC call '{}' was interrupted", method);
            return null;
        }
    }
}
