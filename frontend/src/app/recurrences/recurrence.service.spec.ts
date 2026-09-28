import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RecurrenceService } from './recurrence.service';

describe('RecurrenceService', () => {
  let service: RecurrenceService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(RecurrenceService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('uses the backend for preview and does not duplicate calendar logic', () => {
    service.preview({ firstDueDate: '2027-01-31', lastDueDate: null, frequency: 'MONTHLY' }).subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({});
    const request = http.expectOne('/api/v1/recurrences/calendar-preview');
    expect(request.request.body).toEqual({ firstDueDate: '2027-01-31', lastDueDate: null, frequency: 'MONTHLY' });
    request.flush(['2027-01-31', '2027-02-28', '2027-03-31']);
  });

  it('preserves the idempotency key when creating a definition', () => {
    const data = { description: 'Condomínio', amount: '500.00', valueType: 'FIXED' as const,
      frequency: 'MONTHLY' as const, firstDueDate: '2027-01-31', lastDueDate: null,
      categoryId: null, responsibleUserId: null };
    service.create(data, 'recurrence-key').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({});
    const request = http.expectOne('/api/v1/recurrences');
    expect(request.request.headers.get('Idempotency-Key')).toBe('recurrence-key');
    expect(request.request.body).toEqual(data);
    request.flush({});
  });

  it('loads forecasts and anticipates one occurrence with explicit confirmation', () => {
    service.forecasts().subscribe();
    http.expectOne('/api/v1/recurrences/forecasts').flush({ from: '2026-09', to: '2027-09', occurrences: [] });
    const item = { recurrenceId: 'rec-1', description: 'Seguro', amount: '100.00', estimated: false,
      scheduledDueDate: '2026-10-03', state: 'FORECAST' as const, expenseId: null, actualDueDate: null,
      expenseStatus: null, chargeConfirmed: false };
    service.anticipate(item, 'anticipation-key').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({});
    const request = http.expectOne('/api/v1/recurrences/rec-1/occurrences/2026-10-03/anticipation');
    expect(request.request.headers.get('Idempotency-Key')).toBe('anticipation-key');
    expect(request.request.body).toEqual({ confirmed: true });
    request.flush({ occurrence: item, replayed: false });
  });

  it('confirms a forecast charge on the occurrence identity with its own key', () => {
    const item = { recurrenceId: 'rec-1', description: 'Energia', amount: '180.00', estimated: true,
      scheduledDueDate: '2026-11-10', state: 'FORECAST' as const, expenseId: null, actualDueDate: null,
      expenseStatus: null, chargeConfirmed: false };
    service.confirmForecastCharge(item, '205.40', 'confirm-key').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({});
    const request = http.expectOne('/api/v1/recurrences/rec-1/occurrences/2026-11-10/charge-confirmation');
    expect(request.request.headers.get('Idempotency-Key')).toBe('confirm-key');
    expect(request.request.body).toEqual({ confirmedAmount: '205.40' });
    request.flush({ occurrence: item, replayed: false });
  });

  it('previews and applies changes and closures, sending the key only when applying', () => {
    const change = { version: 2, effectiveDueDate: '2026-10-05', description: 'Luz', amount: '10.00', frequency: 'MONTHLY' as const,
      dueDay: 5, categoryId: null, responsibleUserId: null };
    service.previewChange('rec-1', change).subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({});
    const preview = http.expectOne('/api/v1/recurrences/rec-1/changes/preview');
    expect(preview.request.headers.has('Idempotency-Key')).toBe(false);
    expect(preview.request.body).toEqual(change);
    preview.flush({});
    service.applyChange('rec-1', { ...change, impactToken: 't' }, 'change-key').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({});
    const apply = http.expectOne('/api/v1/recurrences/rec-1/changes');
    expect(apply.request.headers.get('Idempotency-Key')).toBe('change-key');
    expect(apply.request.body.impactToken).toBe('t');
    apply.flush({});
    service.previewClosure('rec-1', { version: 2, lastDueDate: '2026-10-05' }).subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({});
    http.expectOne('/api/v1/recurrences/rec-1/closure/preview').flush({});
    service.applyClosure('rec-1', { version: 2, lastDueDate: '2026-10-05', reason: 'Fim', impactToken: 't' }, 'close-key').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({});
    const close = http.expectOne('/api/v1/recurrences/rec-1/closure');
    expect(close.request.headers.get('Idempotency-Key')).toBe('close-key');
    expect(close.request.body.reason).toBe('Fim');
    close.flush({});
  });
});
