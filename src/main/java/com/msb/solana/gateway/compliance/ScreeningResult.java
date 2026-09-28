package com.msb.solana.gateway.compliance;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Immutable outcome of a compliance screen. Backwards-compatible with the
 * legacy controller payload ({@code address}, {@code riskScore},
 * {@code verdict}, {@code sanctionsMatch}, {@code timestamp}) while adding the
 * structured {@code flags} list and ISO-8601 {@code lastEvaluated} timestamp.
 */
public record ScreeningResult(
        @JsonProperty("address") String address,
        @JsonProperty("verdict") ScreeningVerdict verdict,
        @JsonProperty("riskScore") int riskScore,
        @JsonProperty("flags") List<ScreeningFlag> flags,
        @JsonProperty("sanctionsMatch") boolean sanctionsMatch,
        @JsonProperty("lastEvaluated") Instant lastEvaluated,
        @JsonProperty("timestamp") long timestamp) {

    public static ScreeningResult clearToTransact(String address, Instant evaluatedAt) {
        return new ScreeningResult(
                address,
                ScreeningVerdict.CLEAR_TO_TRANSACT,
                0,
                List.of(),
                false,
                evaluatedAt,
                evaluatedAt.toEpochMilli());
    }

    public static ScreeningResult blocked(String address, ThreatIntelligenceEntry entry, Instant evaluatedAt) {
        return new ScreeningResult(
                address,
                ScreeningVerdict.BLOCKED,
                entry.riskScore(),
                List.of(ScreeningFlag.from(entry)),
                entry.category() == RiskCategory.OFAC_SANCTIONED,
                evaluatedAt,
                evaluatedAt.toEpochMilli());
    }
}
