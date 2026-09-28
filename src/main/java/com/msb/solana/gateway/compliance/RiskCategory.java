package com.msb.solana.gateway.compliance;

/**
 * Risk classification applied to a Solana address flagged by the threat
 * intelligence dataset.
 */
public enum RiskCategory {
    OFAC_SANCTIONED,
    EXPLOIT_DRAINER,
    HIGH_RISK_MIXER
}
