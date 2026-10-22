package com.msb.solana.rwa.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.SolanaPaymentGatewayApplication;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.gateway.serialization.Base58;
import com.msb.solana.gateway.service.ChannelVoucherVerifier;
import com.msb.solana.rwa.entity.AssetToken;
import com.msb.solana.rwa.entity.AssetTokenComplianceStatus;
import com.msb.solana.rwa.entity.Investor;
import com.msb.solana.rwa.entity.KycStatus;
import com.msb.solana.rwa.repository.AssetTokenRepository;
import com.msb.solana.rwa.repository.InvestorRepository;
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

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Finding #3 (RWA parity): {@code SOLANA_RPC_MOCK_MODE} was honoured by the
 * gateway escrow verifier but ignored by {@link com.msb.solana.rwa.rpc.SolanaRpcAdapter},
 * so the documented "deterministic, offline" RWA demo still dialed Devnet.
 *
 * <p>This test points the RPC URL at an unroutable loopback port and enables mock
 * mode. The paid attestation must still be APPROVED, proving the RWA on-chain
 * existence check never touched the network.
 */
@SpringBootTest(
        classes = SolanaPaymentGatewayApplication.class,
        properties = {
                "solana.rpc.url=http://127.0.0.1:1",
                "solana.rpc.mock-mode=true"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RwaAttestationMockModeIntegrationTest {

    private static final String INVESTOR_WALLET = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";
    private static final String ASSET_MINT = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU";
    private static final String CHANNEL_ID = "chan_demo_solana_001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChannelVoucherVerifier voucherVerifier;

    @Autowired
    private PaymentAuditRepository paymentAuditRepository;

    @Autowired
    private InvestorRepository investorRepository;

    @Autowired
    private AssetTokenRepository assetTokenRepository;

    private Ed25519PrivateKeyParameters clientPrivateKey;
    private String clientPubkeyBase58;

    @BeforeEach
    void setUp() {
        paymentAuditRepository.deleteAll();
        voucherVerifier.resetState();
        investorRepository.deleteAll();
        assetTokenRepository.deleteAll();

        investorRepository.save(Investor.builder()
                .fullName("Mock Mode Investor")
                .email("mock-mode@example.com")
                .walletAddress(INVESTOR_WALLET)
                .kycStatus(KycStatus.VERIFIED)
                .country("US")
                .build());

        assetTokenRepository.save(AssetToken.builder()
                .assetName("Mock Mode Vessel Note")
                .valuationUsd(new BigDecimal("250000.00"))
                .mintAddress(ASSET_MINT)
                .complianceStatus(AssetTokenComplianceStatus.COMPLIANT)
                .build());

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
    @DisplayName("A paid attestation is approved offline when RPC mock mode is enabled")
    void paidAttestation_succeedsWithoutNetwork() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "walletAddress", INVESTOR_WALLET,
                "assetMintAddress", ASSET_MINT));

        mockMvc.perform(post("/api/v1/rwa/attest")
                        .header("PAYMENT-SIGNATURE", signedHeader(5_000L, 1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().exists("PAYMENT-RESPONSE"))
                .andExpect(jsonPath("$.allowed", is(true)))
                .andExpect(jsonPath("$.investorStatus", is("VERIFIED")))
                .andExpect(jsonPath("$.assetStatus", is("COMPLIANT")));
    }
}
