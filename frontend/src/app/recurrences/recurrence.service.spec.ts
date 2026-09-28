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
});
