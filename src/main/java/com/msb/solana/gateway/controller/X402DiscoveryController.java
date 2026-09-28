package com.msb.solana.gateway.controller;

import com.msb.solana.gateway.model.X402DiscoveryResponse;
import com.msb.solana.gateway.model.X402DiscoveryResponse.ServiceEndpoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Serves the standard x402 machine-readable discovery document.
 *
 * <p>Exposed at {@code GET /.well-known/x402.json} so clients can discover the
 * gateway's accepted asset, escrow address, and per-call pricing before issuing
 * an HTTP 402 challenge / presenting a payment voucher.
 */
@RestController
public class X402DiscoveryController {

    private static final String GATEWAY_NAME = "Solana Enterprise Payment Gateway";
    private static final String GATEWAY_DESCRIPTION = "Enterprise x402 Payment Channel Gateway on Solana Rails";
    private static final String SCREEN_ADDRESS_PATH = "/api/v1/compliance/screen-address";
    private static final String SCREEN_ADDRESS_METHOD = "POST";
    private static final String SCREEN_ADDRESS_DESCRIPTION =
            "Screen a Solana address against the enterprise sanctions / threat-intelligence registry";

    private final String network;
    private final String escrowAddress;
    private final String asset;
    private final long priceAtomicUnits;
    private final String unit;

    public X402DiscoveryController(
            @Value("${x402.network:solana:devnet}") String network,
            @Value("${x402.escrow-pubkey:8cVJRRDjyrhWvag2h4nFvK4aBBxNmVo31a6zAX1eMtE4}") String escrowAddress,
            @Value("${x402.asset:USDC}") String asset,
            @Value("${x402.price-atomic-units:5000}") long priceAtomicUnits,
            @Value("${x402.unit:per-call}") String unit) {
        this.network = network;
        this.escrowAddress = escrowAddress;
        this.asset = asset;
        this.priceAtomicUnits = priceAtomicUnits;
        this.unit = unit;
    }

    @GetMapping(value = "/.well-known/x402.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<X402DiscoveryResponse> discovery() {
        ServiceEndpoint screenAddress = new ServiceEndpoint(
                SCREEN_ADDRESS_PATH,
                SCREEN_ADDRESS_METHOD,
                priceAtomicUnits,
                unit,
                SCREEN_ADDRESS_DESCRIPTION);

        X402DiscoveryResponse response = new X402DiscoveryResponse(
                2,
                GATEWAY_NAME,
                GATEWAY_DESCRIPTION,
                network,
                escrowAddress,
                asset,
                List.of(screenAddress));

        return ResponseEntity.ok(response);
    }
}
