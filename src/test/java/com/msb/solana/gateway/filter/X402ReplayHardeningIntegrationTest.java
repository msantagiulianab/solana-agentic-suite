package com.msb.solana.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.gateway.serialization.Base58;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fail-closed hardening of the x402 gate (Findings #3 and #5).
 *
 * <ul>
 *   <li>A replay presented after a JVM restart must be rejected with
 *       {@code 403 PAYMENT_REJECTED}, not a leaked {@code 400} or {@code 500}.</li>
 *   <li>Malformed payment headers must return a sanitized {@code 400} that never
 *       echoes exception messages, class names, or database details.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class X402ReplayHardeningIntegrationTest {

    private static final String SCREEN_PATH = "/api/v1/compliance/screen-address";
    private static final String CHANNEL_ID = "chan_demo_solana_001";
    private static final String SCREEN_BODY =
            "{\"address\":\"4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y\"}";

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
        // Isolate the shared H2 ledger and the in-memory watermark between tests.
        auditRepository.deleteAll();
        voucherVerifier.resetState();

        Ed25519KeyPairGenerator keyGen = new Ed25519KeyPairGenerator();
        keyGen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        var keyPair = keyGen.generateKeyPair();
        this.clientPrivateKey = (Ed25519PrivateKeyParameters) keyPair.getPrivate();
        Ed25519PublicKeyParameters pubKey = (Ed25519PublicKeyParameters) keyPair.getPublic();
        this.clientPubkeyBase58 = Base58.encode(pubKey.getEncoded());
    }

    private String signedHeader(long amount, long nonce) throws Exception {
        PaymentVoucher unsigned = new PaymentVoucher(CHANNEL_ID, clientPubkeyBase58, amount, nonce, "");
        byte[] canonicalBytes = unsigned.getCanonicalPayload();

        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, clientPrivateKey);
        signer.update(canonicalBytes, 0, canonicalBytes.length);
        String signature = Base58.encode(signer.generateSignature());

        PaymentVoucher signed = new PaymentVoucher(CHANNEL_ID, clientPubkeyBase58, amount, nonce, signature);
        return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(signed));
    }

    @Test
    @DisplayName("A replay after a restart returns 403 PAYMENT_REJECTED, never a leaked 400/500")
    void replayAfterRestart_returns403FailClosed() throws Exception {
        String header = signedHeader(5_000L, 100L);

        mockMvc.perform(post(SCREEN_PATH)
                        .header("PAYMENT-SIGNATURE", header)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SCREEN_BODY))
                .andExpect(status().isOk());

        // Simulate a JVM restart: the in-memory watermark is lost, the ledger is not.
        voucherVerifier.resetState();

        mockMvc.perform(post(SCREEN_PATH)
                        .header("PAYMENT-SIGNATURE", header)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SCREEN_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("PAYMENT_REJECTED")))
                .andExpect(content().string(not(containsString("constraint"))))
                .andExpect(content().string(not(containsString("duplicate"))))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("com.msb"))));
    }

    @Test
    @DisplayName("A malformed payment header returns a sanitized 400 without internal details")
    void malformedHeader_returnsSanitized400() throws Exception {
        mockMvc.perform(post(SCREEN_PATH)
                        .header("PAYMENT-SIGNATURE", "!!!not-base64!!!")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SCREEN_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("MALFORMED_PAYMENT_HEADER")))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(content().string(not(containsString("Illegal"))))
                .andExpect(content().string(not(containsString("base64 character"))))
                .andExpect(content().string(not(containsString("java."))));
    }

    @Test
    @DisplayName("An unpaid request is challenged with 402 and both payment headers")
    void missingHeader_isChallenged() throws Exception {
        mockMvc.perform(post(SCREEN_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SCREEN_BODY))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.x402Version", is(2)));
    }
}
