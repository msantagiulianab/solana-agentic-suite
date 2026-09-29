package com.msb.solana.rwa.service;

import com.msb.solana.gateway.entity.PaymentAuditRecord;
import com.msb.solana.gateway.repository.PaymentAuditRepository;
import com.msb.solana.rwa.entity.AuditLog;
import com.msb.solana.rwa.entity.AuditLogStatus;
import com.msb.solana.rwa.model.AuditDashboardResponse;
import com.msb.solana.rwa.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles the cockpit dashboard snapshot from the two append-only ledgers.
 *
 * <ul>
 *   <li>{@code totalAttestations} — RWA compliance attestation count</li>
 *   <li>{@code x402PaymentsProcessed} — verified/settled x402 payment count</li>
 *   <li>{@code averageLatencyMs} — in-memory voucher verification SLA budget
 *       (the gateway does not persist per-request latency, so the configured
 *       {@code x402.latency-sla-ms} ceiling is reported)</li>
 *   <li>{@code blockRatePercent} — fail-closed compliance block rate</li>
 * </ul>
 */
@Service
public class AuditDashboardService {

    public static final String SOURCE_RWA_ATTESTATION = "RWA_ATTESTATION";
    public static final String SOURCE_X402_PAYMENT = "X402_PAYMENT";

    private final AuditLogRepository auditLogRepository;
    private final PaymentAuditRepository paymentAuditRepository;
    private final double latencySlaMs;
    private final int recentTransactionLimit;

    public AuditDashboardService(
            AuditLogRepository auditLogRepository,
            PaymentAuditRepository paymentAuditRepository,
            @Value("${x402.latency-sla-ms:5}") double latencySlaMs,
            @Value("${audit.dashboard.recent-limit:50}") int recentTransactionLimit) {
        this.auditLogRepository = auditLogRepository;
        this.paymentAuditRepository = paymentAuditRepository;
        this.latencySlaMs = latencySlaMs;
        this.recentTransactionLimit = recentTransactionLimit;
    }

    @Transactional(readOnly = true)
    public AuditDashboardResponse getDashboard() {
        return new AuditDashboardResponse(
                computeMetrics(),
                getRecentTransactions(),
                Instant.now());
    }

    @Transactional(readOnly = true)
    public AuditDashboardResponse.Metrics getMetrics() {
        return computeMetrics();
    }

    @Transactional(readOnly = true)
    public List<AuditDashboardResponse.RecentTransaction> getRecentTransactions() {
        List<AuditDashboardResponse.RecentTransaction> merged = new ArrayList<>();
        merged.addAll(mapAttestations());
        merged.addAll(mapPayments());
        merged.sort(newestFirst());
        return merged.stream().limit(recentTransactionLimit).toList();
    }

    private AuditDashboardResponse.Metrics computeMetrics() {
        long totalAttestations = auditLogRepository.count();
        long blocked = auditLogRepository.countByStatus(AuditLogStatus.BLOCKED);
        long x402PaymentsProcessed = paymentAuditRepository.count();
        double blockRatePercent = totalAttestations == 0
                ? 0.0
                : roundToSingleDecimal(blocked * 100.0 / totalAttestations);
        return new AuditDashboardResponse.Metrics(
                totalAttestations,
                x402PaymentsProcessed,
                latencySlaMs,
                blockRatePercent);
    }

    private List<AuditDashboardResponse.RecentTransaction> mapAttestations() {
        return auditLogRepository
                .findAllByOrderByTimestampDesc(PageRequest.of(0, recentTransactionLimit))
                .stream()
                .map(log -> toRecentTransaction(log))
                .toList();
    }

    private List<AuditDashboardResponse.RecentTransaction> mapPayments() {
        return paymentAuditRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(0, recentTransactionLimit))
                .stream()
                .map(record -> toRecentTransaction(record))
                .toList();
    }

    private AuditDashboardResponse.RecentTransaction toRecentTransaction(AuditLog log) {
        return new AuditDashboardResponse.RecentTransaction(
                idOrEmpty(log.getId()),
                nullToEmpty(log.getSolanaTransactionSignature()),
                log.getWalletAddress(),
                log.getTimestamp(),
                log.getStatus().name(),
                SOURCE_RWA_ATTESTATION);
    }

    private AuditDashboardResponse.RecentTransaction toRecentTransaction(PaymentAuditRecord record) {
        return new AuditDashboardResponse.RecentTransaction(
                idOrEmpty(record.getId()),
                resolvePaymentHash(record),
                record.getPayerPubkey(),
                record.getCreatedAt(),
                record.getStatus().name(),
                SOURCE_X402_PAYMENT);
    }

    private String resolvePaymentHash(PaymentAuditRecord record) {
        if (record.getTxSignature() != null && !record.getTxSignature().isBlank()) {
            return record.getTxSignature();
        }
        return nullToEmpty(record.getSignature());
    }

    /** Newest first; null timestamps (e.g. unsaved fixtures) sort last. */
    private Comparator<AuditDashboardResponse.RecentTransaction> newestFirst() {
        return (left, right) -> {
            if (left.timestamp() == null && right.timestamp() == null) {
                return 0;
            }
            if (left.timestamp() == null) {
                return 1;
            }
            if (right.timestamp() == null) {
                return -1;
            }
            return right.timestamp().compareTo(left.timestamp());
        };
    }

    private String idOrEmpty(Object id) {
        return id == null ? "" : id.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private double roundToSingleDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
