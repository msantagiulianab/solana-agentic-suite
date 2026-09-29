import { fakeAsync, TestBed, tick, discardPeriodicTasks } from '@angular/core/testing';
import { of } from 'rxjs';

import { AppComponent } from './app.component';
import { AuditService } from './services/audit.service';

describe('AppComponent', () => {
  const auditServiceMock = jasmine.createSpyObj<AuditService>('AuditService', ['getDashboardSnapshot']);

  beforeEach(async () => {
    auditServiceMock.getDashboardSnapshot.and.returnValue(
      of({
        metrics: {
          totalAttestations: 0,
          x402PaymentsProcessed: 0,
          averageLatencyMs: 0,
          blockRatePercent: 0,
        },
        recentTransactions: [],
        updatedAt: new Date().toISOString(),
      }),
    );

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [{ provide: AuditService, useValue: auditServiceMock }],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it(`should have the 'cockpit-ui' title`, () => {
    const fixture = TestBed.createComponent(AppComponent);
    expect(fixture.componentInstance.title).toEqual('cockpit-ui');
  });

  it('should render the dashboard component', fakeAsync(() => {
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    tick(0);
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('app-dashboard')).toBeTruthy();

    discardPeriodicTasks();
  }));
});
