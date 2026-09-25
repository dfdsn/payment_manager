import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ExpenseService } from './expense.service';

describe('ExpenseService', () => {
  let service: ExpenseService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(ExpenseService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('preserves the explicit idempotency key after obtaining CSRF', () => {
    service.create({
      description: 'Energia', amount: '150.00', status: 'PENDING',
      dueDate: '2026-09-30', paymentDate: null, notes: null,
    }, 'operation-key').subscribe();

    http.expectOne('/api/v1/auth/csrf').flush({ headerName: 'X-XSRF-TOKEN' });
    const request = http.expectOne('/api/v1/expenses');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('operation-key');
    expect(request.request.body.amount).toBe('150.00');
    request.flush({});
  });

  it('requests server pagination and stable ordering', () => {
    service.list(2, 20, 'DESCRIPTION', 'DESC').subscribe();
    const request = http.expectOne(candidate => candidate.url === '/api/v1/expenses');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    expect(request.request.params.get('sort')).toBe('DESCRIPTION');
    expect(request.request.params.get('direction')).toBe('DESC');
    request.flush({ content: [], page: 2, size: 20, totalElements: 0, totalPages: 0 });
  });

  it('settles with CSRF, idempotency key, version, payer and effective amount', () => {
    service.settle('expense-id', {
      version: 2, paidAmount: '155.00', paymentDate: '2026-10-01',
      paidByUserId: 'payer-id', paymentNotes: 'Juros',
    }, 'payment-key').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({ headerName: 'X-XSRF-TOKEN' });
    const request = http.expectOne('/api/v1/expenses/expense-id/payment');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('payment-key');
    expect(request.request.body).toEqual({
      version: 2, paidAmount: '155.00', paymentDate: '2026-10-01',
      paidByUserId: 'payer-id', paymentNotes: 'Juros',
    });
    request.flush({});
  });
});
