package com.msb.solana.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Finding #4: {@code x402.network}, {@code x402.asset} and {@code x402.unit}
 * were dead configuration. The HTTP 402 challenge hardcoded
 * {@code solana:devnet} / {@code USDC} / {@code per-call}, and the discovery
 * document advertised a different default escrow address than the filter.
 *
 * <p>This test boots the application with non-default protocol parameters and
 * asserts that the normative {@code PAYMENT-REQUIRED} header, the JSON challenge
 * body, and the {@code /.well-known/x402.json} discovery document all agree on a
 * single source of truth.
 */
@SpringBootTest(properties = {
        "x402.network=solana:mainnet-beta",
        "x402.asset=SOL",
        "x402.unit=per-request",
        "x402.price-atomic-units=1234",
        "x402.escrow-pubkey=8cVJRRDjyrhWvag2h4nFvK4aBBxNmVo31a6zAX1eMtE4"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class X402ChallengeConfigurationIntegrationTest {

    private static final String SCREEN_PATH = "/api/v1/compliance/screen-address";
    private static final String SCREEN_BODY =
            "{\"address\":\"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y\"}";
    private static final String CONFIGURED_ESCROW = "8cVJRRDjyrhWvag2h4nFvK4aBBxNmVo31a6zAX1eMtE4";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("The 402 challenge header and body reflect the configured network/asset/unit/price")
    void challenge_reflectsConfiguredProtocolParameters() throws Exception {
        MvcResult result = mockMvc.perform(post(SCREEN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SCREEN_BODY))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.network", is("solana:mainnet-beta")))
                .andExpect(jsonPath("$.asset", is("SOL")))
                .andExpect(jsonPath("$.unit", is("per-request")))
                .andExpect(jsonPath("$.priceAtomicUnits").value(1234))
                .andExpect(jsonPath("$.escrowAddress", is(CONFIGURED_ESCROW)))
                .andReturn();

        String header = result.getResponse().getHeader("PAYMENT-REQUIRED");
        assertThat(header).isNotBlank();
        JsonNode decoded = objectMapper.readTree(Base64.getDecoder().decode(header));
        assertThat(decoded.path("network").asText()).isEqualTo("solana:mainnet-beta");
        assertThat(decoded.path("asset").asText()).isEqualTo("SOL");
        assertThat(decoded.path("unit").asText()).isEqualTo("per-request");
        assertThat(decoded.path("priceAtomicUnits").asLong()).isEqualTo(1234L);
        assertThat(decoded.path("escrowAddress").asText()).isEqualTo(CONFIGURED_ESCROW);
    }

    @Test
    @DisplayName("The discovery document agrees with the challenge on every protocol parameter")
    void discovery_reflectsSameConfiguredParameters() throws Exception {
        mockMvc.perform(get("/.well-known/x402.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network", is("solana:mainnet-beta")))
                .andExpect(jsonPath("$.asset", is("SOL")))
                .andExpect(jsonPath("$.escrowAddress", is(CONFIGURED_ESCROW)))
                .andExpect(jsonPath("$.services[0].priceAtomicUnits").value(1234))
                .andExpect(jsonPath("$.services[0].unit", is("per-request")));
    }
}
