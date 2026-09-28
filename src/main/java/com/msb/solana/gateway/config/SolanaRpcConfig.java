package com.msb.solana.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Provides the JDK {@link HttpClient} bean used by {@code SolanaRpcClient}.
 *
 * <p>Kept in its own configuration so unit tests can inject a mocked client
 * directly while production wires the real, time-boxed client.
 */
@Configuration
public class SolanaRpcConfig {

    @Bean
    public HttpClient solanaRpcHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
}
