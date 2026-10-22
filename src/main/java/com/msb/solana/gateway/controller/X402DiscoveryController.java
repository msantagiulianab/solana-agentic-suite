package com.msb.solana.gateway.controller;

import com.msb.solana.gateway.model.PaymentRequiredChallenge;
import com.msb.solana.gateway.model.X402DiscoveryResponse;
import com.msb.solana.gateway.model.X402DiscoveryResponse.ServiceEndpoint;
import com.msb.solana.gateway.service.X402ChallengeFactory;
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
 *
 * <p>The document is derived from the same {@link X402ChallengeFactory} as the
 * live challenge, so discovery and enforcement can never advertise different
 * networks, assets, units, prices, or escrow addresses.
 */
@RestController
public class X402DiscoveryController {

    private static final String GATEWAY_NAME = "Solana Enterprise Payment Gateway";
    private static final String GATEWAY_DESCRIPTION = "Enterprise x402 Payment Channel Gateway on Solana Rails";
    private static final String SCREEN_ADDRESS_PATH = "/api/v1/compliance/screen-address";
    private static final String SCREEN_ADDRESS_METHOD = "POST";
    private static final String SCREEN_ADDRESS_DESCRIPTION =
            "Screen a Solana address against the enterprise sanctions / threat-intelligence registry";

    private final X402ChallengeFactory challengeFactory;

    public X402DiscoveryController(X402ChallengeFactory challengeFactory) {
        this.challengeFactory = challengeFactory;
    }

    @GetMapping(value = "/.well-known/x402.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<X402DiscoveryResponse> discovery() {
        PaymentRequiredChallenge challenge = challengeFactory.createChallenge();

        ServiceEndpoint screenAddress = new ServiceEndpoint(
                SCREEN_ADDRESS_PATH,
                SCREEN_ADDRESS_METHOD,
                challenge.priceAtomicUnits(),
                challenge.unit(),
                SCREEN_ADDRESS_DESCRIPTION);

        X402DiscoveryResponse response = new X402DiscoveryResponse(
                challenge.x402Version(),
                GATEWAY_NAME,
                GATEWAY_DESCRIPTION,
                challenge.network(),
                challenge.escrowAddress(),
                challenge.asset(),
                List.of(screenAddress));

        return ResponseEntity.ok(response);
    }
}
