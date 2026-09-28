import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { InstallmentPurchaseService } from './installment-purchase.service';

describe('InstallmentPurchaseService', () => {
  let service: InstallmentPurchaseService;
  let http: HttpTestingController;
  const data = { description: 'Sofá', totalAmount: '100.00', installmentCount: 3, firstDueDate: '2027-01-31',
    categoryId: null, responsibleUserId: null };
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(InstallmentPurchaseService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('asks the backend for the calculation instead of dividing cents in the browser', () => {
    service.preview(data).subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({});
    const request = http.expectOne('/api/v1/installment-purchases/preview');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.has('Idempotency-Key')).toBe(false);
    expect(request.request.body).toEqual(data);
    request.flush({});
  });

  it('creates with the CSRF token first and the idempotency key of the reviewed request', () => {
    service.create(data, 'purchase-key').subscribe();
    http.expectOne('/api/v1/auth/csrf').flush({});
    const request = http.expectOne('/api/v1/installment-purchases');
    expect(request.request.headers.get('Idempotency-Key')).toBe('purchase-key');
    expect(request.request.body).toEqual(data);
    request.flush({});
  });

  it('reads purchases with progress and one purchase detail without writing anything', () => {
    service.list(2, 5).subscribe();
    const list = http.expectOne(r => r.url === '/api/v1/installment-purchases');
    expect(list.request.method).toBe('GET');
    expect(list.request.params.get('page')).toBe('2');
    expect(list.request.params.get('size')).toBe('5');
    list.flush({ items: [], page: 2, size: 5, totalItems: 0 });
    service.get('p-1').subscribe();
    http.expectOne('/api/v1/installment-purchases/p-1').flush({});
  });
});
