import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subject, Subscription, of, timer } from 'rxjs';
import { catchError, switchMap, takeUntil } from 'rxjs/operators';

import {
  AuditLogEntry,
  AuditService,
  ComplianceVerdict,
  EMPTY_SYSTEM_METRICS,
  SystemMetrics,
} from '../services/audit.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit, OnDestroy {
  metrics: SystemMetrics = { ...EMPTY_SYSTEM_METRICS };
  transactions: AuditLogEntry[] = [];

  loading = true;
  error: string | null = null;
  lastUpdated: Date | null = null;

  private readonly pollIntervalMs = 5000;
  private readonly destroy$ = new Subject<void>();
  private pollSubscription?: Subscription;

  constructor(private readonly auditService: AuditService) {}

  ngOnInit(): void {
    this.pollSubscription = timer(0, this.pollIntervalMs)
      .pipe(
        takeUntil(this.destroy$),
        switchMap(() =>
          this.auditService.getDashboardSnapshot().pipe(
            catchError((err: unknown) => {
              this.error = this.describeError(err);
              return of(null);
            }),
          ),
        ),
      )
      .subscribe((snapshot) => {
        this.loading = false;
        if (snapshot) {
          this.metrics = snapshot.metrics;
          this.transactions = snapshot.recentTransactions;
          this.lastUpdated = new Date(snapshot.updatedAt);
          this.error = null;
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.pollSubscription?.unsubscribe();
  }

  shorten(value: string, head = 6, tail = 4): string {
    if (!value) {
      return '—';
    }
    if (value.length <= head + tail + 1) {
      return value;
    }
    return `${value.slice(0, head)}…${value.slice(-tail)}`;
  }

  verdictBadgeClasses(status: ComplianceVerdict): string {
    const approved = status === 'APPROVED' || status === 'VERIFIED' || status === 'SETTLED';
    return approved
      ? 'inline-flex items-center rounded-full border border-emerald-500/30 bg-emerald-500/10 px-2.5 py-1 text-xs font-semibold text-emerald-300'
      : 'inline-flex items-center rounded-full border border-rose-500/30 bg-rose-500/10 px-2.5 py-1 text-xs font-semibold text-rose-300';
  }

  private describeError(err: unknown): string {
    if (err instanceof Error && err.message) {
      return err.message;
    }
    if (err && typeof err === 'object' && 'status' in err) {
      const status = (err as { status: number }).status;
      return `Backend request failed with status ${status}.`;
    }
    return 'Unable to reach the audit backend.';
  }
}
