package com.msb.solana.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.serialization.Base58;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.gateway.service.ChannelVoucherVerifier;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class X402ProtocolIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChannelVoucherVerifier voucherVerifier;

    @Autowired
    private PaymentAuditRepository auditRepository;

    private Ed25519PrivateKeyParameters clientPrivateKey;
    private String clientPubkeyBase58;

    @BeforeEach
    void setUp() {
        // Isolate each test run from in-memory singleton state
        voucherVerifier.resetState();

        Ed25519KeyPairGenerator keyGen = new Ed25519KeyPairGenerator();
        keyGen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        var keyPair = keyGen.generateKeyPair();
        this.clientPrivateKey = (Ed25519PrivateKeyParameters) keyPair.getPrivate();
        Ed25519PublicKeyParameters pubKey = (Ed25519PublicKeyParameters) keyPair.getPublic();
        this.clientPubkeyBase58 = Base58.encode(pubKey.getEncoded());
    }

    private String createSignedVoucherHeader(String channelId, long amount, long nonce, boolean tamper) throws Exception {
        PaymentVoucher voucher = new PaymentVoucher(channelId, clientPubkeyBase58, amount, nonce, "");
        byte[] canonicalBytes = voucher.getCanonicalPayload();

        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, clientPrivateKey);
        signer.update(canonicalBytes, 0, canonicalBytes.length);
        String signature = Base58.encode(signer.generateSignature());

        if (tamper) {
            // Tamper by altering the amount in the payload without updating the signature
            voucher = new PaymentVoucher(channelId, clientPubkeyBase58, amount + 9999L, nonce, signature);
        } else {
            voucher = new PaymentVoucher(channelId, clientPubkeyBase58, amount, nonce, signature);
        }

        byte[] jsonBytes = objectMapper.writeValueAsBytes(voucher);
        return Base64.getEncoder().encodeToString(jsonBytes);
    }

    @Test
    @DisplayName("Request without payment must trigger HTTP 402 challenge with PAYMENT-REQUIRED header")
    void testMissingPaymentReturns402Challenge() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/screen-address")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"11111111111111111111111111111111\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().exists("PAYMENT-REQUIRED"))
                .andExpect(header().exists("X-PAYMENT-REQUIRED"))
                .andExpect(jsonPath("$.x402Version", is(2)))
                .andExpect(jsonPath("$.scheme", is("channel")))
                .andExpect(jsonPath("$.network", is("solana:devnet")))
                .andExpect(jsonPath("$.asset", is("USDC")));
    }

    @Test
    @DisplayName("Valid signed voucher grants HTTP 200 OK and returns PAYMENT-RESPONSE receipt header")
    void testValidVoucherGrantsAccess() throws Exception {
        String voucherHeader = createSignedVoucherHeader("chan_demo_solana_001", 5000L, 10L, false);

        mockMvc.perform(post("/api/v1/compliance/screen-address")
                        .header("PAYMENT-SIGNATURE", voucherHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("PAYMENT-RESPONSE"))
                .andExpect(header().exists("X-PAYMENT-RESPONSE"))
                .andExpect(jsonPath("$.address", is("4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y")))
                .andExpect(jsonPath("$.verdict", is("CLEAR_TO_TRANSACT")));

        // The successfully verified voucher must be appended to the audit ledger.
        assertThat(auditRepository.findByChannelId("chan_demo_solana_001"))
                .anyMatch(record -> record.getNonce() == 10L);
    }

    @Test
    @DisplayName("Replayed nonce must be rejected with HTTP 403 Forbidden")
    void testReplayedNonceFailsClosed() throws Exception {
        String firstVoucher = createSignedVoucherHeader("chan_demo_solana_001", 5000L, 50L, false);

        // First presentation succeeds
        mockMvc.perform(post("/api/v1/compliance/screen-address")
                        .header("PAYMENT-SIGNATURE", firstVoucher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y\"}"))
                .andExpect(status().isOk());

        // Second presentation with the exact same nonce (<= 50) fails closed
        mockMvc.perform(post("/api/v1/compliance/screen-address")
                        .header("PAYMENT-SIGNATURE", firstVoucher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("PAYMENT_REJECTED")));
    }

    @Test
    @DisplayName("Tampered payload signature must fail closed with HTTP 403 Forbidden")
    void testTamperedSignatureFailsClosed() throws Exception {
        String tamperedVoucher = createSignedVoucherHeader("chan_demo_solana_001", 5000L, 80L, true);

        mockMvc.perform(post("/api/v1/compliance/screen-address")
                        .header("PAYMENT-SIGNATURE", tamperedVoucher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("PAYMENT_REJECTED")));
    }
}