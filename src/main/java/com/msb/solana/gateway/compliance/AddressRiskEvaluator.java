package com.msb.solana.gateway.compliance;

import com.msb.solana.gateway.serialization.SolanaAddressValidator;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Evaluates a raw Solana address against the threat intelligence registry.
 *
 * <p>Validation and matching run entirely in memory (no RPC calls) so the
 * screening endpoint stays within the sub-5ms hot-path latency budget.
 */
@Service
public class AddressRiskEvaluator {

    private final SolanaAddressValidator addressValidator;
    private final ThreatIntelligenceRegistry threatIntelligenceRegistry;

    public AddressRiskEvaluator(SolanaAddressValidator addressValidator,
                                ThreatIntelligenceRegistry threatIntelligenceRegistry) {
        this.addressValidator = addressValidator;
        this.threatIntelligenceRegistry = threatIntelligenceRegistry;
    }

    /**
     * @param rawAddress raw Base58 Solana address to screen
     * @return immutable {@link ScreeningResult}
     * @throws InvalidSolanaAddressException when the address is not valid Base58
     *                                       or does not decode to exactly 32 bytes
     */
    public ScreeningResult evaluate(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) {
            throw new InvalidSolanaAddressException("Solana address is required");
        }

        String address = rawAddress.trim();
        if (!addressValidator.isValid(address)) {
            throw new InvalidSolanaAddressException(
                    "Invalid Solana Base58 address: must decode to exactly 32 bytes");
        }

        Instant evaluatedAt = Instant.now();
        return threatIntelligenceRegistry.lookup(address)
                .map(entry -> ScreeningResult.blocked(address, entry, evaluatedAt))
                .orElseGet(() -> ScreeningResult.clearToTransact(address, evaluatedAt));
    }
}
