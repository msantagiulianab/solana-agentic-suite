package com.msb.solana.gateway.compliance;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single immutable record in the threat intelligence dataset.
 *
 * @param address     Base58 Solana public key
 * @param category    risk classification
 * @param riskScore   numeric risk severity (0-100)
 * @param description human-readable rationale for the listing
 */
public record ThreatIntelligenceEntry(
        @JsonProperty("address") String address,
        @JsonProperty("category") RiskCategory category,
        @JsonProperty("riskScore") int riskScore,
        @JsonProperty("description") String description) {
}
