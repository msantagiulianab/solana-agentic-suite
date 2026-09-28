package com.msb.solana.gateway.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the x402 machine-readable discovery document is served without any
 * payment voucher (i.e. the payment filter exempts {@code /.well-known/**}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class X402DiscoveryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /.well-known/x402.json returns HTTP 200 discovery document without payment")
    void discoveryEndpoint_returnsMachineReadableDocument() throws Exception {
        mockMvc.perform(get("/.well-known/x402.json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.x402Version", is(2)))
                .andExpect(jsonPath("$.name", is("Solana Enterprise Payment Gateway")))
                .andExpect(jsonPath("$.description").isNotEmpty())
                .andExpect(jsonPath("$.network", is("solana:devnet")))
                .andExpect(jsonPath("$.escrowAddress", is("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")))
                .andExpect(jsonPath("$.asset", is("USDC")))
                .andExpect(jsonPath("$.services", hasSize(1)))
                .andExpect(jsonPath("$.services[0].path", is("/api/v1/compliance/screen-address")))
                .andExpect(jsonPath("$.services[0].method", is("POST")))
                .andExpect(jsonPath("$.services[0].priceAtomicUnits").value(5000))
                .andExpect(jsonPath("$.services[0].unit", is("per-call")))
                .andExpect(jsonPath("$.services[0].description").isNotEmpty());
    }
}
