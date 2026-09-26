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

  it('sends one protected request for the complete atomic batch', () => {
    const data = { items: [{ expenseId: 'one', version: 2 }, { expenseId: 'two', version: 4 }],
      paymentDate: '2026-10-01', paidByUserId: 'payer-id', confirmed: true };
    service.settleBatch(data, 'batch-key').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({ headerName: 'X-XSRF-TOKEN' });
    const request = http.expectOne('/api/v1/expenses/batch-payment');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('batch-key');
    expect(request.request.body).toEqual(data);
    request.flush({ operationId: 'batch', replayed: false, items: [] });
  });

  it('loads one expense and corrects it with CSRF, version and idempotency key', () => {
    service.get('expense-id').subscribe();
    const get = http.expectOne('/api/v1/expenses/expense-id');
    expect(get.request.method).toBe('GET');
    get.flush({ id: 'expense-id' });

    const correction = {
      version: 3, status: 'PENDING' as const, description: 'Energia corrigida',
      amount: '151.00', dueDate: '2026-10-02', notes: null,
    };
    service.correct('expense-id', correction, 'correction-key').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({ headerName: 'X-XSRF-TOKEN' });
    const put = http.expectOne('/api/v1/expenses/expense-id');
    expect(put.request.method).toBe('PUT');
    expect(put.request.headers.get('Idempotency-Key')).toBe('correction-key');
    expect(put.request.body).toEqual(correction);
    put.flush({});
  });

  it('loads a deterministic page of expense history', () => {
    service.history('expense-id', 2, 10).subscribe();
    const request = http.expectOne(candidate => candidate.url === '/api/v1/expenses/expense-id/history');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('10');
    request.flush({ content: [], page: 2, size: 10, totalElements: 0, totalPages: 0 });
  });

  it('reverses and cancels through protected versioned idempotent actions', () => {
    for (const action of [
      { invoke: () => service.reversePayment('expense-id', 4, 'Pagamento incorreto', 'reverse-key'), path: 'payment-reversal', key: 'reverse-key', reason: 'Pagamento incorreto' },
      { invoke: () => service.cancel('expense-id', 5, 'Duplicada', 'cancel-key'), path: 'cancellation', key: 'cancel-key', reason: 'Duplicada' },
    ]) {
      action.invoke().subscribe();
      http.expectOne('/api/v1/auth/csrf').flush({ headerName: 'X-XSRF-TOKEN' });
      const request = http.expectOne(`/api/v1/expenses/expense-id/${action.path}`);
      expect(request.request.method).toBe('POST');
      expect(request.request.headers.get('Idempotency-Key')).toBe(action.key);
      expect(request.request.body.reason).toBe(action.reason);
      request.flush({});
    }
  });
});
