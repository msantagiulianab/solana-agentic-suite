package com.msb.solana.gateway.compliance;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The risk metadata attached to a blocked screening verdict. A single address
 * resolves to a single flag entry from the threat intelligence dataset.
 */
public record ScreeningFlag(
        @JsonProperty("category") RiskCategory category,
        @JsonProperty("riskScore") int riskScore,
        @JsonProperty("description") String description) {

    public static ScreeningFlag from(ThreatIntelligenceEntry entry) {
        return new ScreeningFlag(entry.category(), entry.riskScore(), entry.description());
    }
}
