package com.msb.solana.rwa.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated read model backing the cockpit-ui dashboard.
 *
 * <p>Served by {@code GET /api/v1/rwa/audit}. Combines the off-chain RWA
 * compliance metrics (attestations and fail-closed block rate) with the x402
 * payment audit ledger into a single pollable snapshot. The JSON field names
 * mirror the {@code AuditService} contract consumed by the Angular cockpit.
 *
 * @param metrics            calculated aggregate metrics
 * @param recentTransactions newest-first merged feed of RWA attestations and x402 payments
 * @param updatedAt          instant at which the snapshot was assembled
 */
public record AuditDashboardResponse(
        @JsonProperty("metrics") Metrics metrics,
        @JsonProperty("recentTransactions") List<RecentTransaction> recentTransactions,
        @JsonProperty("updatedAt") Instant updatedAt) {

    /** Calculated aggregate metrics powering the four dashboard cards. */
    public record Metrics(
            @JsonProperty("totalAttestations") long totalAttestations,
            @JsonProperty("x402PaymentsProcessed") long x402PaymentsProcessed,
            @JsonProperty("averageLatencyMs") double averageLatencyMs,
            @JsonProperty("blockRatePercent") double blockRatePercent) {
    }

    /**
     * A single row in the recent-transactions table.
     *
     * @param id              stable record identifier (stringified across both ledgers)
     * @param transactionHash on-chain signature when present, otherwise the voucher signature
     * @param payerPublicKey  base58 payer wallet address
     * @param timestamp       instant the record was appended
     * @param status          verdict: {@code APPROVED|BLOCKED} (RWA) or {@code VERIFIED|SETTLED} (x402)
     * @param source          origin ledger ({@code RWA_ATTESTATION} or {@code X402_PAYMENT})
     */
    public record RecentTransaction(
            @JsonProperty("id") String id,
            @JsonProperty("transactionHash") String transactionHash,
            @JsonProperty("payerPublicKey") String payerPublicKey,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("status") String status,
            @JsonProperty("source") String source) {
    }
}
