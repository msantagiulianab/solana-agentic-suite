import { discardPeriodicTasks, fakeAsync, TestBed, tick } from '@angular/core/testing';
import { of } from 'rxjs';

import { DashboardComponent } from './dashboard.component';
import { AuditDashboardResponse, AuditService } from '../services/audit.service';

describe('DashboardComponent', () => {
  const snapshot: AuditDashboardResponse = {
    metrics: {
      totalAttestations: 12,
      x402PaymentsProcessed: 340,
      averageLatencyMs: 3.7,
      blockRatePercent: 18.4,
    },
    recentTransactions: [
      {
        id: '1',
        transactionHash: '3zKcze3Q9DDRui2YCMeTPsBs3mxymxMN3oCvNDVKkrbgtZsR6CVfrLaMPd1kVHjoiAYEqTLNYxAzeABmdBMWSWhm',
        payerPublicKey: '4Nd1mBQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y',
        timestamp: '2026-09-28T12:00:00Z',
        status: 'APPROVED',
      },
      {
        id: '2',
        transactionHash: '5xQKze4R1EESvi3ZDNfUQtC4nyzOzoNP4pDwWLlMscrhtauT7DWgsMfQe2kWiJpKbBzFrMOZyCBfCnoCNFWTYjn',
        payerPublicKey: '7XyZbQtrMJVYVfKf2PJy9NZGibCcTRxpETqdrBHu19Y',
        timestamp: '2026-09-28T12:01:00Z',
        status: 'BLOCKED',
      },
    ],
    updatedAt: '2026-09-28T12:01:00Z',
  };

  const auditServiceMock = jasmine.createSpyObj<AuditService>('AuditService', ['getDashboardSnapshot']);

  beforeEach(async () => {
    auditServiceMock.getDashboardSnapshot.and.returnValue(of(snapshot));

    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [{ provide: AuditService, useValue: auditServiceMock }],
    }).compileComponents();
  });

  it('should create the component', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    tick(0);
    expect(fixture.componentInstance).toBeTruthy();
    discardPeriodicTasks();
  }));

  it('should render metrics and transaction verdicts', fakeAsync(() => {
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    tick(0);
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Total Attestations');
    expect(el.textContent).toContain('Autonomous x402 Micro-Payments Processed');
    expect(el.textContent).toContain('Average Latency');
    expect(el.textContent).toContain('Fail-Closed Compliance Block Rate');
    expect(el.textContent).toContain('Approved');
    expect(el.textContent).toContain('Blocked');

    discardPeriodicTasks();
  }));
});
