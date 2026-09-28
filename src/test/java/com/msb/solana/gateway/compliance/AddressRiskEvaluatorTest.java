package com.msb.solana.gateway.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.solana.gateway.serialization.SolanaAddressValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AddressRiskEvaluator}: blocklist matching, clean
 * clearance, and validation failure.
 */
class AddressRiskEvaluatorTest {

    private static final String OFAC_SANCTIONED = "Fc1EwQUZyTEagaDvA1utHXCcZNyG1x2PLt2DfNu1cJdH";
    private static final String EXPLOIT_DRAINER = "8MycFWXtDrHyEw7pQgzMXFrKUT94N9uso962Jtp3vt4X";
    private static final String CLEAN = "4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y";

    private AddressRiskEvaluator evaluator;

    @BeforeEach
    void setUp() {
        ThreatIntelligenceRegistry registry = new ThreatIntelligenceRegistry(new ObjectMapper());
        evaluator = new AddressRiskEvaluator(new SolanaAddressValidator(), registry);
    }

    @Test
    @DisplayName("Known OFAC SDN address is BLOCKED with riskScore 100")
    void knownSanctionedAddress_isBlocked() {
        ScreeningResult result = evaluator.evaluate(OFAC_SANCTIONED);

        assertThat(result.verdict()).isEqualTo(ScreeningVerdict.BLOCKED);
        assertThat(result.riskScore()).isEqualTo(100);
        assertThat(result.sanctionsMatch()).isTrue();
        assertThat(result.flags()).hasSize(1);
        assertThat(result.flags().get(0).category()).isEqualTo(RiskCategory.OFAC_SANCTIONED);
        assertThat(result.lastEvaluated()).isNotNull();
        assertThat(result.timestamp()).isPositive();
    }

    @Test
    @DisplayName("Known exploit/drainer marker is BLOCKED with the EXPLOIT_DRAINER flag")
    void knownDrainer_isBlocked() {
        ScreeningResult result = evaluator.evaluate(EXPLOIT_DRAINER);

        assertThat(result.verdict()).isEqualTo(ScreeningVerdict.BLOCKED);
        assertThat(result.riskScore()).isEqualTo(100);
        assertThat(result.sanctionsMatch()).isFalse();
        assertThat(result.flags())
                .extracting(ScreeningFlag::category)
                .containsExactly(RiskCategory.EXPLOIT_DRAINER);
    }

    @Test
    @DisplayName("Clean valid Solana address is CLEAR_TO_TRANSACT with riskScore 0 and no flags")
    void cleanAddress_isClearToTransact() {
        ScreeningResult result = evaluator.evaluate(CLEAN);

        assertThat(result.verdict()).isEqualTo(ScreeningVerdict.CLEAR_TO_TRANSACT);
        assertThat(result.riskScore()).isZero();
        assertThat(result.flags()).isEmpty();
        assertThat(result.sanctionsMatch()).isFalse();
        assertThat(result.lastEvaluated()).isNotNull();
    }

    @Test
    @DisplayName("Malformed Base58 address throws a validation exception (translates to 400)")
    void malformedBase58_throwsValidationException() {
        // Illegal characters not present in the Base58 alphabet.
        assertThatThrownBy(() -> evaluator.evaluate("0OIl"))
                .isInstanceOf(InvalidSolanaAddressException.class)
                .hasMessageContaining("Invalid Solana Base58 address");

        // Valid characters but the decoded length is not exactly 32 bytes.
        assertThatThrownBy(() -> evaluator.evaluate("abc123"))
                .isInstanceOf(InvalidSolanaAddressException.class)
                .hasMessageContaining("Invalid Solana Base58 address");
    }
}
