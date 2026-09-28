package com.msb.solana.gateway.service;

import com.msb.solana.gateway.rpc.SolanaRpcClient;
import com.msb.solana.gateway.rpc.model.AccountInfoResponse;
import com.msb.solana.gateway.rpc.model.AccountInfoValue;
import com.msb.solana.gateway.rpc.model.RpcContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SolanaEscrowVerifier}: cache hits, mock-mode fallback,
 * and fail-closed behavior on missing / zero-balance accounts.
 */
@ExtendWith(MockitoExtension.class)
class SolanaEscrowVerifierTest {

    private static final String ESCROW = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU";

    @Mock
    private SolanaRpcClient rpcClient;

    private AccountInfoResponse account(long lamports) {
        return new AccountInfoResponse(
                new RpcContext(1L),
                new AccountInfoValue(lamports, List.of(), "11111111111111111111111111111111", false, 0L));
    }

    @Test
    @DisplayName("Mock mode returns the demo ceiling without any RPC traffic")
    void mockMode_returnsDemoCeilingWithoutRpc() {
        SolanaEscrowVerifier verifier = new SolanaEscrowVerifier(rpcClient, true, 5000L, ESCROW);

        assertThat(verifier.getVerifiedDepositCeiling(SolanaEscrowVerifier.MOCK_CHANNEL_ID))
                .isEqualTo(SolanaEscrowVerifier.MOCK_CEILING_ATOMIC);

        verifyNoInteractions(rpcClient);
    }

    @Test
    @DisplayName("Mock mode returns the demo ceiling for the smoke-test channel")
    void mockMode_returnsCeilingForSmokeTestChannel() {
        SolanaEscrowVerifier verifier = new SolanaEscrowVerifier(rpcClient, true, 5000L, ESCROW);

        assertThat(verifier.getVerifiedDepositCeiling(SolanaEscrowVerifier.SMOKE_TEST_CHANNEL_ID))
                .isEqualTo(SolanaEscrowVerifier.MOCK_CEILING_ATOMIC);

        verifyNoInteractions(rpcClient);
    }

    @Test
    @DisplayName("Mock mode fails closed for an unknown channel")
    void mockMode_unknownChannelFailsClosed() {
        SolanaEscrowVerifier verifier = new SolanaEscrowVerifier(rpcClient, true, 5000L, ESCROW);

        assertThat(verifier.getVerifiedDepositCeiling("unknown_channel")).isZero();

        verifyNoInteractions(rpcClient);
    }

    @Test
    @DisplayName("Real mode queries the escrow once and serves subsequent calls from the cache")
    void realMode_queriesEscrowAndCachesResult() {
        SolanaEscrowVerifier verifier = new SolanaEscrowVerifier(rpcClient, false, 5000L, ESCROW);
        when(rpcClient.getAccountInfo(ESCROW)).thenReturn(Optional.of(account(1_234_567L)));

        assertThat(verifier.getVerifiedDepositCeiling("chan_1")).isEqualTo(1_234_567L);
        assertThat(verifier.getVerifiedDepositCeiling("chan_1")).isEqualTo(1_234_567L);

        verify(rpcClient, times(1)).getAccountInfo(ESCROW);
    }

    @Test
    @DisplayName("Real mode re-queries once the short cache TTL has expired")
    void realMode_cacheExpiresAndReQueries() {
        SolanaEscrowVerifier verifier = new SolanaEscrowVerifier(rpcClient, false, 0L, ESCROW);
        when(rpcClient.getAccountInfo(ESCROW)).thenReturn(Optional.of(account(100L)));

        assertThat(verifier.getVerifiedDepositCeiling("chan_2")).isEqualTo(100L);
        assertThat(verifier.getVerifiedDepositCeiling("chan_2")).isEqualTo(100L);

        verify(rpcClient, times(2)).getAccountInfo(ESCROW);
    }

    @Test
    @DisplayName("Real mode fails closed when the on-chain account is missing")
    void realMode_missingAccountFailsClosed() {
        SolanaEscrowVerifier verifier = new SolanaEscrowVerifier(rpcClient, false, 5000L, ESCROW);
        when(rpcClient.getAccountInfo(ESCROW)).thenReturn(Optional.empty());

        assertThat(verifier.getVerifiedDepositCeiling("chan_3")).isZero();
    }

    @Test
    @DisplayName("Real mode fails closed when the on-chain account has zero lamports")
    void realMode_zeroLamportsFailsClosed() {
        SolanaEscrowVerifier verifier = new SolanaEscrowVerifier(rpcClient, false, 5000L, ESCROW);
        when(rpcClient.getAccountInfo(ESCROW)).thenReturn(Optional.of(account(0L)));

        assertThat(verifier.getVerifiedDepositCeiling("chan_4")).isZero();
    }

    @Test
    @DisplayName("A blank channel id fails closed without any RPC traffic")
    void blankChannelIdFailsClosed() {
        SolanaEscrowVerifier verifier = new SolanaEscrowVerifier(rpcClient, false, 5000L, ESCROW);

        assertThat(verifier.getVerifiedDepositCeiling("   ")).isZero();

        verifyNoInteractions(rpcClient);
    }
}
