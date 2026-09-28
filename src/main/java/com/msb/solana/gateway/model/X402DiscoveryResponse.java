package com.msb.solana.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Machine-readable x402 discovery document served at {@code /.well-known/x402.json}.
 *
 * <p>Advertises the gateway's payment terms and paid service endpoints so clients
 * can discover and negotiate payment without a prior out-of-band exchange.
 *
 * @param x402Version   x402 protocol version (2)
 * @param name          human-readable gateway name
 * @param description   human-readable gateway description
 * @param network       Solana network identifier (e.g. solana:devnet)
 * @param escrowAddress Base58 escrow/settlement wallet for payment channels
 * @param asset         accepted settlement asset (e.g. USDC)
 * @param services      paid service endpoints exposed by the gateway
 */
public record X402DiscoveryResponse(
        @JsonProperty("x402Version") int x402Version,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("network") String network,
        @JsonProperty("escrowAddress") String escrowAddress,
        @JsonProperty("asset") String asset,
        @JsonProperty("services") List<ServiceEndpoint> services
) {
    /**
     * A single paid service endpoint and its per-call price.
     */
    public record ServiceEndpoint(
            @JsonProperty("path") String path,
            @JsonProperty("method") String method,
            @JsonProperty("priceAtomicUnits") long priceAtomicUnits,
            @JsonProperty("unit") String unit,
            @JsonProperty("description") String description
    ) {
    }
}
