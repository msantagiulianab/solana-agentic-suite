import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';

/**
 * Compliance outcome persisted by the gateway immutable audit ledger.
 * Mirrors the backend {@code AuditLogStatus} enum (APPROVED | BLOCKED).
 */
export type ComplianceVerdict = 'APPROVED' | 'BLOCKED' | 'VERIFIED' | 'SETTLED';

/** A single row in the recent-transactions table. */
export interface AuditLogEntry {
  /** Stable record id (backend {@code AuditLog.id}). */
  id: string;
  /** Base58 Solana transaction signature (empty while pending settlement). */
  transactionHash: string;
  /** Base58 payer wallet address that presented the x402 voucher. */
  payerPublicKey: string;
  /** ISO-8601 instant the attempt was recorded. */
  timestamp: string;
  /** Off-chain gatekeeping verdict. */
  status: ComplianceVerdict;
  /** Origin ledger (RWA_ATTESTATION | X402_PAYMENT). */
  source?: string;
  /** Human-readable compliance rationale. */
  reason?: string;
  /** Action type (e.g. CHECK_ELIGIBILITY). */
  action?: string;
}

/** Aggregated gateway metrics powering the dashboard cards. */
export interface SystemMetrics {
  totalAttestations: number;
  x402PaymentsProcessed: number;
  averageLatencyMs: number;
  blockRatePercent: number;
}

/** Combined payload returned by the audit endpoint. */
export interface AuditDashboardResponse {
  metrics: SystemMetrics;
  recentTransactions: AuditLogEntry[];
  updatedAt: string;
}

export const EMPTY_SYSTEM_METRICS: SystemMetrics = {
  totalAttestations: 0,
  x402PaymentsProcessed: 0,
  averageLatencyMs: 0,
  blockRatePercent: 0,
};

/**
 * Fetches the live audit trail and system telemetry from the Spring Boot
 * gateway. The backing contract is {@code GET /api/v1/rwa/audit} (with
 * {@code /logs} and {@code /status} sub-resources), served by the
 * {@code cockpit-ui} dev proxy from {@code http://localhost:8080}.
 */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly auditEndpoint = '/api/v1/rwa/audit';

  constructor(private readonly http: HttpClient) {}

  /** Full dashboard snapshot: metrics + recent transactions in one call. */
  getDashboardSnapshot(): Observable<AuditDashboardResponse> {
    return this.http.get<AuditDashboardResponse>(this.auditEndpoint).pipe(
      map((snapshot) => ({
        metrics: snapshot.metrics ?? EMPTY_SYSTEM_METRICS,
        recentTransactions: snapshot.recentTransactions ?? [],
        updatedAt: snapshot.updatedAt ?? new Date().toISOString(),
      })),
    );
  }

  /** Recent transaction rows only. */
  getAuditLogs(): Observable<AuditLogEntry[]> {
    return this.http
      .get<AuditLogEntry[]>(`${this.auditEndpoint}/logs`)
      .pipe(catchError(() => of([])));
  }

  /** Aggregated metrics only. */
  getSystemStatus(): Observable<SystemMetrics> {
    return this.http
      .get<SystemMetrics>(`${this.auditEndpoint}/status`)
      .pipe(catchError(() => of(EMPTY_SYSTEM_METRICS)));
  }
}
