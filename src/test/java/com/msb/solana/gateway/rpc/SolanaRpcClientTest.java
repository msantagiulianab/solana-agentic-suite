package com.msb.solana.gateway.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.rpc.model.AccountInfoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit tests for {@link SolanaRpcClient}.
 *
 * <p>The JDK {@link HttpClient} is mocked so no live Devnet RPC traffic is ever
 * attempted. Covers JSON-RPC request serialization (captured from the HTTP
 * request body) and response parsing for {@code getAccountInfo} and
 * {@code getLatestBlockhash}, including fail-closed behavior on missing
 * accounts, JSON-RPC error payloads, and HTTP error statuses.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SolanaRpcClientTest {

    private static final String RPC_URL = "https://api.devnet.solana.com";
    private static final String PUBKEY = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU";

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private ObjectMapper objectMapper;
    private SolanaRpcClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        client = new SolanaRpcClient(objectMapper, httpClient, RPC_URL, false);
    }

    private void stubResponse(int statusCode, String body) throws Exception {
        when(httpClient.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(statusCode);
        when(httpResponse.body()).thenReturn(body);
    }

    @Test
    @DisplayName("getAccountInfo serializes a JSON-RPC request and parses the account value")
    void getAccountInfo_serializesJsonRpcRequestAndParsesAccount() throws Exception {
        String body = """
                {"jsonrpc":"2.0","result":{"context":{"slot":1234},"value":{"data":["","base64"],"executable":false,"lamports":2500000000,"owner":"11111111111111111111111111111111","space":0}},"id":1}
                """;
        stubResponse(200, body);

        Optional<AccountInfoResponse> result = client.getAccountInfo(PUBKEY);

        assertThat(result).isPresent();
        assertThat(result.get().value().lamports()).isEqualTo(2_500_000_000L);
        assertThat(result.get().value().owner()).isEqualTo("11111111111111111111111111111111");
        assertThat(result.get().context().slot()).isEqualTo(1234L);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());

        HttpRequest request = captor.getValue();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo(RPC_URL);
        assertThat(request.headers().firstValue("Content-Type")).contains("application/json");

        JsonNode json = objectMapper.readTree(requestBody(request));
        assertThat(json.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(json.get("method").asText()).isEqualTo("getAccountInfo");
        assertThat(json.get("id").asLong()).isEqualTo(1L);
        assertThat(json.get("params").get(0).asText()).isEqualTo(PUBKEY);
        assertThat(json.get("params").get(1).get("encoding").asText()).isEqualTo("base64");
        assertThat(json.get("params").get(1).get("commitment").asText()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("getLatestBlockhash serializes confirmed commitment and parses the blockhash")
    void getLatestBlockhash_serializesCommitmentAndParsesBlockhash() throws Exception {
        String body = """
                {"jsonrpc":"2.0","result":{"context":{"slot":1234},"value":{"blockhash":"GhN4g8N4ZzY6kLq9vR2wT1uXp8sM3cB5dF7jH9nQ1oA2","lastValidBlockHeight":123456}},"id":2}
                """;
        stubResponse(200, body);

        Optional<String> result = client.getLatestBlockhash();

        assertThat(result).contains("GhN4g8N4ZzY6kLq9vR2wT1uXp8sM3cB5dF7jH9nQ1oA2");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());

        JsonNode json = objectMapper.readTree(requestBody(captor.getValue()));
        assertThat(json.get("method").asText()).isEqualTo("getLatestBlockhash");
        assertThat(json.get("params").get(0).get("commitment").asText()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("getAccountInfo returns empty when the account does not exist")
    void getAccountInfo_returnsEmptyWhenAccountDoesNotExist() throws Exception {
        String body = """
                {"jsonrpc":"2.0","result":{"context":{"slot":1234},"value":null},"id":1}
                """;
        stubResponse(200, body);

        assertThat(client.getAccountInfo(PUBKEY)).isEmpty();
    }

    @Test
    @DisplayName("getAccountInfo returns empty on a JSON-RPC error payload")
    void getAccountInfo_returnsEmptyOnJsonRpcError() throws Exception {
        String body = """
                {"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid param"},"id":1}
                """;
        stubResponse(200, body);

        assertThat(client.getAccountInfo(PUBKEY)).isEmpty();
    }

    @Test
    @DisplayName("getLatestBlockhash returns empty on an HTTP error status")
    void getLatestBlockhash_returnsEmptyOnHttpError() throws Exception {
        stubResponse(500, "internal error");

        assertThat(client.getLatestBlockhash()).isEmpty();
    }

    @Test
    @DisplayName("getLatestBlockhash returns empty when the value is missing")
    void getLatestBlockhash_returnsEmptyWhenValueMissing() throws Exception {
        String body = """
                {"jsonrpc":"2.0","result":{"context":{"slot":1234},"value":null},"id":2}
                """;
        stubResponse(200, body);

        assertThat(client.getLatestBlockhash()).isEmpty();
    }

    @Test
    @DisplayName("sendTransaction serializes base64 encoding + confirmed preflight and parses the signature")
    void sendTransaction_serializesConfigAndParsesSignature() throws Exception {
        String body = """
                {"jsonrpc":"2.0","result":"5Fy1kG8Q1rLm2NtXp3VcB7dE9wZ4uY6hJ7kL0mN1oPqAbCdEfGhIjKlMnOpQrStUvWxYz","id":3}
                """;
        stubResponse(200, body);

        String wireTx = "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        Optional<String> result = client.sendTransaction(wireTx);

        assertThat(result).contains("5Fy1kG8Q1rLm2NtXp3VcB7dE9wZ4uY6hJ7kL0mN1oPqAbCdEfGhIjKlMnOpQrStUvWxYz");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());

        JsonNode json = objectMapper.readTree(requestBody(captor.getValue()));
        assertThat(json.get("method").asText()).isEqualTo("sendTransaction");
        assertThat(json.get("params").get(0).asText()).isEqualTo(wireTx);
        assertThat(json.get("params").get(1).get("encoding").asText()).isEqualTo("base64");
        assertThat(json.get("params").get(1).get("preflightCommitment").asText()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("sendTransaction returns empty on a JSON-RPC error payload")
    void sendTransaction_returnsEmptyOnJsonRpcError() throws Exception {
        String body = """
                {"jsonrpc":"2.0","error":{"code":-32002,"message":"Transaction simulation failed"},"id":3}
                """;
        stubResponse(200, body);

        assertThat(client.sendTransaction("AQAAAA")).isEmpty();
    }

    @Test
    @DisplayName("sendTransaction in mock mode returns a deterministic signature without network calls")
    void sendTransaction_mockModeReturnsDeterministicSignatureWithoutNetwork() {
        SolanaRpcClient mockClient = new SolanaRpcClient(objectMapper, httpClient, RPC_URL, true);

        String wireTx = "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        Optional<String> first = mockClient.sendTransaction(wireTx);
        Optional<String> second = mockClient.sendTransaction(wireTx);

        assertThat(first).isPresent();
        assertThat(first).isEqualTo(second);
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("getLatestBlockhash in mock mode returns a deterministic blockhash without network calls")
    void getLatestBlockhash_mockModeReturnsDeterministicBlockhashWithoutNetwork() {
        SolanaRpcClient mockClient = new SolanaRpcClient(objectMapper, httpClient, RPC_URL, true);

        assertThat(mockClient.getLatestBlockhash()).isPresent();
        assertThat(mockClient.getLatestBlockhash()).isEqualTo(mockClient.getLatestBlockhash());
        verifyNoInteractions(httpClient);
    }

    private static String requestBody(HttpRequest request) {
        return request.bodyPublisher()
                .map(SolanaRpcClientTest::collectPublisherBody)
                .orElse("");
    }

    private static String collectPublisherBody(HttpRequest.BodyPublisher publisher) {
        List<ByteBuffer> chunks = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                chunks.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ByteBuffer chunk : chunks) {
            byte[] bytes = new byte[chunk.remaining()];
            chunk.get(bytes);
            out.writeBytes(bytes);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}


