package com.msb.solana.rwa.controller;

import com.msb.solana.rwa.model.AuditDashboardResponse;
import com.msb.solana.rwa.service.AuditDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only cockpit observability endpoint backing the cockpit-ui dashboard.
 *
 * <p>{@code GET /api/v1/rwa/audit} is exempt from the x402 payment filter (see
 * {@code X402PaymentFilter#shouldNotFilter}) because it is a non-mutating
 * observability resource, not a metered service call.
 */
@RestController
@RequestMapping("/api/v1/rwa")
public class AuditController {

    private final AuditDashboardService auditDashboardService;

    public AuditController(AuditDashboardService auditDashboardService) {
        this.auditDashboardService = auditDashboardService;
    }

    @GetMapping("/audit")
    public AuditDashboardResponse audit() {
        return auditDashboardService.getDashboard();
    }

    @GetMapping("/audit/status")
    public AuditDashboardResponse.Metrics status() {
        return auditDashboardService.getMetrics();
    }

    @GetMapping("/audit/logs")
    public List<AuditDashboardResponse.RecentTransaction> logs() {
        return auditDashboardService.getRecentTransactions();
    }
}
