package com.msb.solana.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Solana Agentic Suite entrypoint.
 *
 * <p>The component, entity, and repository scans are widened to
 * {@code com.msb.solana} so both the {@code gateway} (x402 payment channels)
 * and the {@code rwa} (Token-2022 compliance/attestation) domains are wired into
 * a single Spring context.
 */
@SpringBootApplication(scanBasePackages = "com.msb.solana")
@EntityScan(basePackages = "com.msb.solana")
@EnableJpaRepositories(basePackages = "com.msb.solana")
public class SolanaPaymentGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(SolanaPaymentGatewayApplication.class, args);
    }
}