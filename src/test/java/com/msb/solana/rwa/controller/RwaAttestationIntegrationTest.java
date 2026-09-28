package com.msb.solana.rwa.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.SolanaPaymentGatewayApplication;
import com.msb.solana.gateway.model.PaymentVoucher;
import com.msb.solana.gateway.serialization.Base58;
import com.msb.solana.gateway.service.ChannelVoucherVerifier;
import com.msb.solana.rwa.entity.AssetToken;
import com.msb.solana.rwa.entity.AssetTokenComplianceStatus;
import com.msb.solana.rwa.entity.Investor;
import com.msb.solana.rwa.entity.KycStatus;
import com.msb.solana.rwa.repository.AssetTokenRepository;
import com.msb.solana.rwa.repository.InvestorRepository;
import com.msb.solana.rwa.rpc.SolanaRpcAdapter;
import com.msb.solana.rwa.rpc.dto.AccountInfo;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end MockMvc integration test for {@code POST /api/v1/rwa/attest}.
 *
 * <p>Verifies the x402 gatekeeping contract: an unpaid attestation call is
 * challenged with {@code 402 Payment Required}, while a call presenting a valid
 * signed {@code PAYMENT-SIGNATURE} voucher reaches the compliance engine and
 * returns a {@code 200 OK} verdict with a {@code PAYMENT-RESPONSE} receipt.
 * The RPC boundary is mocked so the suite stays offline and deterministic.
 */
@SpringBootTest(classes = SolanaPaymentGatewayApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RwaAttestationIntegrationTest {

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
    private InvestorRepository investorRepository;

    @Autowired
    private AssetTokenRepository assetTokenRepository;

    @MockitoBean
    private SolanaRpcAdapter solanaRpcAdapter;

    private Ed25519PrivateKeyParameters clientPrivateKey;
    private String clientPubkeyBase58;

    @BeforeEach
    void setUp() {
        voucherVerifier.resetState();

        Ed25519KeyPairGenerator keyGen = new Ed25519KeyPairGenerator();
        keyGen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        var keyPair = keyGen.generateKeyPair();
        this.clientPrivateKey = (Ed25519PrivateKeyParameters) keyPair.getPrivate();
        Ed25519PublicKeyParameters pubKey = (Ed25519PublicKeyParameters) keyPair.getPublic();
        this.clientPubkeyBase58 = Base58.encode(pubKey.getEncoded());

        // Happy-path fixtures: a KYC-verified investor and a compliant asset token.
        investorRepository.deleteAll();
        assetTokenRepository.deleteAll();

        investorRepository.save(Investor.builder()
                .fullName("Verified Investor")
                .email("verified@example.com")
                .walletAddress(INVESTOR_WALLET)
                .kycStatus(KycStatus.VERIFIED)
                .country("US")
                .build());

        assetTokenRepository.save(AssetToken.builder()
                .assetName("Maritime Vessel Note")
                .valuationUsd(new BigDecimal("250000.00"))
                .mintAddress(ASSET_MINT)
                .complianceStatus(AssetTokenComplianceStatus.COMPLIANT)
                .build());

        // The investor's wallet exists on-chain (mocked RPC boundary).
        when(solanaRpcAdapter.getAccountInfo(INVESTOR_WALLET))
                .thenReturn(new AccountInfo("11111111111111111111111111111111", 0L, false, 0L));
    }

    private String signedVoucherHeader(String channelId, long amount, long nonce) throws Exception {
        PaymentVoucher voucher = new PaymentVoucher(channelId, clientPubkeyBase58, amount, nonce, "");
        byte[] canonicalBytes = voucher.getCanonicalPayload();

        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, clientPrivateKey);
        signer.update(canonicalBytes, 0, canonicalBytes.length);
        String signature = Base58.encode(signer.generateSignature());

        PaymentVoucher signed = new PaymentVoucher(channelId, clientPubkeyBase58, amount, nonce, signature);
        return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(signed));
    }

    private String requestBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "walletAddress", INVESTOR_WALLET,
                "assetMintAddress", ASSET_MINT));
    }

    @Test
    @DisplayName("Unpaid attestation request is challenged with HTTP 402 Payment Required")
    void attestWithoutPayment_isChallenged() throws Exception {
        mockMvc.perform(post("/api/v1/rwa/attest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isPaymentRequired())
                .andExpect(header().exists("PAYMENT-REQUIRED"))
                .andExpect(header().exists("X-PAYMENT-REQUIRED"));
    }

    @Test
    @DisplayName("Paid attestation request returns HTTP 200 with PAYMENT-RESPONSE and compliance verdict")
    void attestWithPayment_returnsComplianceResponse() throws Exception {
        String voucherHeader = signedVoucherHeader(CHANNEL_ID, 5000L, 1L);

        mockMvc.perform(post("/api/v1/rwa/attest")
                        .header("PAYMENT-SIGNATURE", voucherHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(header().exists("PAYMENT-RESPONSE"))
                .andExpect(header().exists("X-PAYMENT-RESPONSE"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.investorStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.assetStatus").value("COMPLIANT"));
    }
}
