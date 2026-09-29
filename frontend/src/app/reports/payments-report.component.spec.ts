import { HttpErrorResponse, HttpParams, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { CategoryService } from '../expenses/category.service';
import { ExpenseService } from '../expenses/expense.service';
import { AccountAccessService } from '../identity/account-access.service';
import { PaymentsReportComponent } from './payments-report.component';
import { PaymentReport, PaymentRow, ReportService, formatInstant, monthBounds, paymentFields } from './report.service';

const row = (id: string, extra: Partial<PaymentRow> = {}): PaymentRow => ({
  expenseId: id, description: `Conta ${id}`, origin: 'ONE_OFF', installment: null, dueDate: '2026-09-25',
  chargeAmount: '150.00', chargeConfirmed: true, paidAmount: '155.00', adjustment: '5.00', paymentDate: '2026-10-02',
  payerUserId: 'u1', payerDisplayName: 'Ana', recordedByUserId: 'u2', recordedByDisplayName: 'Bia',
  recordedAt: '2026-10-02T15:00:00Z', batchPayment: false, categoryName: null, responsibleDisplayName: null,
  correctionCount: 0, lastCorrection: null, ...extra,
});

const report = (month = '2026-10', extra: Partial<PaymentReport> = {}): PaymentReport => ({
  month, periodStart: `${month}-01`, periodEnd: monthBounds(month).to, dateBasis: 'PAYMENT_DATE',
  timeZone: 'America/Sao_Paulo',
  indicators: { count: 6, paidTotal: '1177.33', chargeTotal: '1162.33', adjustmentIncrease: '25.00',
    adjustmentDiscount: '10.00', adjustmentNet: '15.00' },
  content: [
    row('a'),
    row('b', { origin: 'INSTALLMENT', installment: { purchaseId: 'p', number: 1, count: 3 }, batchPayment: true,
      chargeAmount: '333.33', paidAmount: '333.33', adjustment: '0.00', dueDate: null }),
    row('c', { adjustment: '-10.00', correctionCount: 2, lastCorrection: { actorUserId: 'u2', actorDisplayName: 'Bia',
      correctedAt: '2026-10-03T01:30:00Z', changedFields: ['paymentDate', 'paidByUserId'] } }),
  ],
  page: 0, size: 20, totalElements: 6, totalPages: 1, sort: 'PAYMENT_DATE', direction: 'ASC', ...extra,
});

describe('PaymentsReportComponent', () => {
  let fixture: ComponentFixture<PaymentsReportComponent>;
  const reports = { payments: vi.fn() };
  const text = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  const byTestId = (id: string) => (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`)?.textContent?.trim();

  beforeEach(async () => {
    vi.clearAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-01T02:30:00Z'));
    reports.payments.mockImplementation((month: string) => of(report(month)));
    await TestBed.configureTestingModule({
      imports: [PaymentsReportComponent],
      providers: [provideRouter([]), { provide: ReportService, useValue: reports },
        { provide: ExpenseService, useValue: { filterOptions: () => of({ responsiblePeople: [], payerPeople: [
          { userId: 'u1', displayName: 'Ana', activeMember: true }] }) } },
        { provide: CategoryService, useValue: { list: () => of([]) } },
        { provide: AccountAccessService, useValue: { context: () => of({ userId: 'u1', timeZone: 'America/Sao_Paulo' }) } }],
    }).compileComponents();
    fixture = TestBed.createComponent(PaymentsReportComponent);
    fixture.detectChanges();
  });

  afterEach(() => vi.useRealTimers());

  it('opens the current month by payment date and shows payer, recorder, batch and correction author', () => {
    expect(reports.payments).toHaveBeenCalledWith('2026-10', expect.objectContaining({ search: undefined }), 0, 20,
      'PAYMENT_DATE', 'ASC');
    expect(reports.payments.mock.calls[0][1]).not.toHaveProperty('status');
    expect(text()).toContain('Outubro de 2026');
    expect(text()).toContain('Base temporal: data do pagamento de 01/10/2026 a 31/10/2026');
    expect(text()).toContain('Somente quitações ativas');
    expect(text()).toContain('outra população');
    expect(byTestId('payments-paid-total')).toBe('R$\u00a01.177,33');
    expect(byTestId('payments-count')).toContain('6 pagamento(s)');
    expect(byTestId('payments-charge-total')).toBe('R$\u00a01.162,33');
    expect(byTestId('payments-adjustment-net')).toBe('+R$\u00a015,00');
    expect(text()).toContain('acréscimos +R$ 25,00, descontos -R$ 10,00');
    expect(text()).toContain('Pago por Ana');
    expect(text()).toContain('Por Bia em 02/10/2026, 12:00');
    expect(text()).toContain('Em lote, por Bia');
    expect(text()).toContain('Parcela 1/3');
    expect(text()).toContain('Sem vencimento');
    expect(text()).toContain('-R$ 10,00');
    // 01:30 UTC on 3 October is still 2 October in São Paulo.
    expect(byTestId('payment-correction')).toBe('Corrigida por Bia em 02/10/2026, 22:30: data do pagamento, pagador (2 correções)');
    expect(text()).not.toContain('Pendente');
    expect(text()).not.toContain('Saldo');
  });

  it('applies filters and ordering to totals and page and moves between pages and months', () => {
    reports.payments.mockImplementation((month: string) => of(report(month, { totalPages: 3, totalElements: 45 })));
    const component = fixture.componentInstance;
    component.filterForm.setValue({ search: ' luz ', category: component.none, responsible: 'u9', payerUserId: 'u1',
      sort: 'PAID_AMOUNT', direction: 'DESC' });
    component.applyFilters();
    const filters = { search: 'luz', categoryId: undefined, withoutCategory: true, responsibleUserId: 'u9',
      withoutResponsible: undefined, payerUserId: 'u1' };
    expect(reports.payments).toHaveBeenLastCalledWith('2026-10', filters, 0, 20, 'PAID_AMOUNT', 'DESC');
    fixture.detectChanges();
    expect(text()).toContain('Filtros aplicados a todos os indicadores');
    component.nextPage();
    expect(reports.payments).toHaveBeenLastCalledWith('2026-10', filters, 1, 20, 'PAID_AMOUNT', 'DESC');
    component.previousPage();
    expect(reports.payments).toHaveBeenLastCalledWith('2026-10', filters, 0, 20, 'PAID_AMOUNT', 'DESC');
    component.goTo('2026-12');
    component.nextMonth();
    expect(reports.payments).toHaveBeenLastCalledWith('2027-01', filters, 0, 20, 'PAID_AMOUNT', 'DESC');
    component.clearFilters();
    expect(reports.payments).toHaveBeenLastCalledWith('2027-01', expect.objectContaining({ payerUserId: undefined }), 0, 20,
      'PAYMENT_DATE', 'ASC');
  });

  it('shows an empty month and never keeps stale numbers after a failure', () => {
    reports.payments.mockReturnValue(of(report('2026-09', { content: [], totalElements: 0, totalPages: 0,
      indicators: { count: 0, paidTotal: '0.00', chargeTotal: '0.00', adjustmentIncrease: '0.00',
        adjustmentDiscount: '0.00', adjustmentNet: '0.00' } })));
    fixture.componentInstance.previousMonth();
    fixture.detectChanges();
    expect(text()).toContain('Nenhum pagamento neste mês com os filtros escolhidos.');
    expect(byTestId('payments-adjustment-net')).toBe('R$\u00a00,00');

    reports.payments.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    fixture.componentInstance.nextMonth();
    fixture.detectChanges();
    expect(text()).toContain('Não foi possível carregar os pagamentos');
    expect(byTestId('payments-paid-total')).toBeUndefined();

    const late = new Subject<PaymentReport>();
    reports.payments.mockReturnValueOnce(late).mockImplementation((month: string) => of(report(month)));
    fixture.componentInstance.goTo('2026-07');
    fixture.componentInstance.goTo('2026-08');
    late.next(report('2026-07', { indicators: { ...report().indicators, paidTotal: '999.00' } }));
    fixture.detectChanges();
    expect(fixture.componentInstance.report()?.month).toBe('2026-08');
    expect(byTestId('payments-paid-total')).toBe('R$\u00a01.177,33');
  });
});

describe('payment report service', () => {
  it('requests the payment view with page and ordering and without a situation filter', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(ReportService);
    const http = TestBed.inject(HttpTestingController);
    service.payments('2026-10', { payerUserId: 'u1', withoutCategory: true }, 2, 50, 'DESCRIPTION', 'DESC').subscribe();
    const request = http.expectOne(candidate => candidate.url === '/api/v1/reports/payments');
    const params: HttpParams = request.request.params;
    expect(params.get('month')).toBe('2026-10');
    expect(params.get('payerUserId')).toBe('u1');
    expect(params.get('withoutCategory')).toBe('true');
    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('50');
    expect(params.get('sort')).toBe('DESCRIPTION');
    expect(params.get('direction')).toBe('DESC');
    expect(params.has('status')).toBe(false);
    request.flush(report());
    http.verify();
  });

  it('formats instants in the space time zone and names corrected fields', () => {
    expect(formatInstant('2026-10-01T02:30:00Z', 'America/Sao_Paulo')).toBe('30/09/2026, 23:30');
    expect(paymentFields(['amount', 'paidAmount', 'other'])).toBe('valor da cobrança, valor pago, other');
  });
});
