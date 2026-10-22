package com.msb.solana.rwa.rpc;

import com.msb.solana.rwa.rpc.dto.AccountInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Finding: the RWA RPC adapter ignored {@code SOLANA_RPC_MOCK_MODE}. This test
 * proves that mock mode never touches the HTTP client and still reports a
 * deterministic existing wallet, which is what makes the offline demo truthful.
 */
class SolanaRpcAdapterMockModeTest {

    @Test
    @DisplayName("Mock mode returns a deterministic existing wallet without any HTTP call")
    void mockMode_returnsExistingWalletWithoutHttp() {
        RestClient.Builder builder = Mockito.mock(RestClient.Builder.class);
        RestClient restClient = Mockito.mock(RestClient.class);
        when(builder.build()).thenReturn(restClient);

        SolanaRpcAdapter adapter = new SolanaRpcAdapter(
                builder, "http://127.0.0.1:1", 1_000L, true);

        AccountInfo info = adapter.getAccountInfo("4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y");

        assertThat(info.exists()).isTrue();
        assertThat(info.owner()).isEqualTo(SolanaRpcAdapter.MOCK_WALLET_OWNER);
        assertThat(info.lamports()).isZero();
        verifyNoInteractions(restClient);
    }
}
