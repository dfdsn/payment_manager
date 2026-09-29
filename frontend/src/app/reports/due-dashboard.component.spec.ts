import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { CategoryService } from '../expenses/category.service';
import { ExpenseService } from '../expenses/expense.service';
import { AccountAccessService } from '../identity/account-access.service';
import { DueDashboardComponent } from './due-dashboard.component';
import {
  DueDashboard, ReportService, currentMonth, formatCurrency, formatSigned, monthBounds, shiftMonth,
} from './report.service';

const dashboard = (overrides: Partial<DueDashboard['indicators']> = {}, month = '2026-10'): DueDashboard => ({
  month, periodStart: `${month}-01`, periodEnd: monthBounds(month).to, dateBasis: 'DUE_DATE', today: '2026-10-15',
  timeZone: 'America/Sao_Paulo',
  indicators: {
    plannedCount: 8, plannedTotal: '2792.33', plannedEstimated: '180.00', paidCount: 5, paidTotal: '1022.33',
    pendingCount: 3, pendingTotal: '1780.00', pendingEstimated: '180.00', overdueCount: 1, overdueTotal: '1500.00',
    overdueEstimated: '0.00', adjustmentIncrease: '20.00', adjustmentDiscount: '10.00', adjustmentNet: '10.00',
    ...overrides,
  },
  previousPending: { dueBefore: `${month}-01`, count: 1, total: '800.00', estimated: '0.00', overdueCount: 1, overdueTotal: '800.00' },
});

const expense = (id: string, extra: object = {}) => ({
  id, origin: 'ONE_OFF', installment: null, chargeConfirmed: true, description: `Conta ${id}`, amount: '100.00',
  currency: 'BRL', status: 'PENDING', dueDate: '2026-10-10', paymentDate: null, paidAmount: null, paidByUserId: null,
  referenceDate: '2026-10-10', overdue: true, categoryName: null, categoryId: null, responsibleUserId: null,
  responsibleDisplayName: null, notes: null, createdByDisplayName: 'Ana', paidByDisplayName: null,
  createdAt: '2026-10-01T12:00:00Z', version: 0, history: [], ...extra,
});

describe('DueDashboardComponent', () => {
  let fixture: ComponentFixture<DueDashboardComponent>;
  const reports = { dueDashboard: vi.fn() };
  const expenses = { list: vi.fn(), filterOptions: vi.fn() };
  const text = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  const byTestId = (id: string) => (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`)?.textContent?.trim();

  beforeEach(async () => {
    vi.clearAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    // 02:30 UTC on 1 November is still 31 October in São Paulo: the current month is October.
    vi.setSystemTime(new Date('2026-11-01T02:30:00Z'));
    reports.dueDashboard.mockImplementation((month: string) => of(dashboard({}, month)));
    expenses.list.mockReturnValue(of({ content: [expense('a'), expense('b', { status: 'CANCELLED', overdue: false }),
      expense('c', { status: 'PAID', overdue: false, paidAmount: '155.00', paymentDate: '2026-11-02', dueDate: null,
        referenceDate: '2026-10-20' }), expense('d', { origin: 'INSTALLMENT', installment: { purchaseId: 'p', number: 1, count: 3 },
        chargeConfirmed: false })], page: 0, size: 20, totalElements: 4, totalPages: 1, sort: 'REFERENCE_DATE', direction: 'ASC' }));
    expenses.filterOptions.mockReturnValue(of({ responsiblePeople: [{ userId: 'u1', displayName: 'Ana', activeMember: true }],
      payerPeople: [{ userId: 'u2', displayName: 'Bia', activeMember: false }] }));
    await TestBed.configureTestingModule({
      imports: [DueDashboardComponent],
      providers: [provideRouter([]), { provide: ReportService, useValue: reports }, { provide: ExpenseService, useValue: expenses },
        { provide: CategoryService, useValue: { list: () => of([{ id: 'cat', name: 'Moradia', archived: false, version: 0, updatedAt: '' }]) } },
        { provide: AccountAccessService, useValue: { context: () => of({ userId: 'u1', timeZone: 'America/Sao_Paulo' }) } }],
    }).compileComponents();
    fixture = TestBed.createComponent(DueDashboardComponent);
    fixture.detectChanges();
  });

  afterEach(() => vi.useRealTimers());

  it('opens the current month of the space time zone and shows every indicator with its meaning', () => {
    expect(reports.dueDashboard).toHaveBeenCalledWith('2026-10', expect.objectContaining({ status: 'ACTIVE' }));
    expect(expenses.list).toHaveBeenCalledWith(0, 20, 'REFERENCE_DATE', 'ASC', expect.objectContaining({
      dateFrom: '2026-10-01', dateTo: '2026-10-31', dateBasis: 'DUE_DATE', status: 'ACTIVE' }));
    expect(text()).toContain('Outubro de 2026');
    expect(byTestId('planned-total')).toBe('R$ 2.792,33');
    expect(byTestId('paid-total')).toBe('R$ 1.022,33');
    expect(byTestId('pending-total')).toBe('R$ 1.780,00');
    expect(byTestId('overdue-total')).toBe('R$ 1.500,00');
    expect(byTestId('adjustment-net')).toBe('+R$ 10,00');
    expect(text()).toContain('Inclui R$ 180,00 a confirmar');
    expect(text()).toContain('Não é previsto menos pago');
    expect(text()).toContain('acréscimos +R$ 20,00, descontos -R$ 10,00');
    expect(text()).toContain('Base temporal: vencimento de 01/10/2026 a 31/10/2026');
    expect(text()).toContain('Canceladas não entram nos totais');
    expect(byTestId('previous-total')).toContain('1 conta(s) · R$ 800,00');
    expect(text()).toContain('Cancelada (fora dos totais)');
    expect(text()).toContain('Atrasada');
    expect(text()).toContain('20/10/2026 (pagamento)');
    expect(text()).toContain('Parcela 1/3');
    expect(text()).toContain('a confirmar');
  });

  it('applies the same filters to the indicators and to the list', () => {
    fixture.componentInstance.filterForm.setValue({ search: '  luz ', category: fixture.componentInstance.none,
      responsible: 'u1', payerUserId: 'u2', status: 'OVERDUE' });
    fixture.componentInstance.applyFilters();
    const filters = { search: 'luz', categoryId: undefined, withoutCategory: true, responsibleUserId: 'u1',
      withoutResponsible: undefined, payerUserId: 'u2', status: 'OVERDUE' };
    expect(reports.dueDashboard).toHaveBeenLastCalledWith('2026-10', filters);
    expect(expenses.list).toHaveBeenLastCalledWith(0, 20, 'REFERENCE_DATE', 'ASC',
      { ...filters, dateFrom: '2026-10-01', dateTo: '2026-10-31', dateBasis: 'DUE_DATE' });
    fixture.detectChanges();
    expect(text()).toContain('Filtros aplicados a todos os indicadores');
    fixture.componentInstance.clearFilters();
    expect(reports.dueDashboard).toHaveBeenLastCalledWith('2026-10', expect.objectContaining({ status: 'ACTIVE', search: undefined }));
  });

  it('navigates between months across the year boundary', () => {
    fixture.componentInstance.goTo('2026-12');
    fixture.componentInstance.nextMonth();
    expect(reports.dueDashboard).toHaveBeenLastCalledWith('2027-01', expect.anything());
    expect(expenses.list).toHaveBeenLastCalledWith(0, 20, 'REFERENCE_DATE', 'ASC',
      expect.objectContaining({ dateFrom: '2027-01-01', dateTo: '2027-01-31' }));
    fixture.componentInstance.previousMonth();
    fixture.componentInstance.previousMonth();
    expect(reports.dueDashboard).toHaveBeenLastCalledWith('2026-11', expect.anything());
    fixture.componentInstance.monthControl.setValue('2024-02');
    fixture.componentInstance.chooseMonth();
    expect(expenses.list).toHaveBeenLastCalledWith(0, 20, 'REFERENCE_DATE', 'ASC',
      expect.objectContaining({ dateFrom: '2024-02-01', dateTo: '2024-02-29' }));
  });

  it('shows an empty month clearly', () => {
    reports.dueDashboard.mockReturnValue(of({ ...dashboard({ plannedCount: 0, plannedTotal: '0.00', plannedEstimated: '0.00',
      paidCount: 0, paidTotal: '0.00', pendingCount: 0, pendingTotal: '0.00', pendingEstimated: '0.00', overdueCount: 0,
      overdueTotal: '0.00', adjustmentIncrease: '0.00', adjustmentDiscount: '0.00', adjustmentNet: '0.00' }),
      previousPending: { dueBefore: '2026-09-01', count: 0, total: '0.00', estimated: '0.00', overdueCount: 0, overdueTotal: '0.00' } }));
    expenses.list.mockReturnValue(of({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, sort: 'REFERENCE_DATE', direction: 'ASC' }));
    fixture.componentInstance.previousMonth();
    fixture.detectChanges();
    expect(text()).toContain('Nenhuma conta vence neste mês com os filtros escolhidos.');
    expect(byTestId('previous-empty')).toBe('Nenhuma pendência anterior com estes filtros.');
    expect(byTestId('adjustment-net')).toBe('R$ 0,00');
    expect(text()).not.toContain('a confirmar.');
  });

  it('removes stale numbers on failure and ignores late responses of an older selection', () => {
    reports.dueDashboard.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    fixture.componentInstance.nextMonth();
    fixture.detectChanges();
    expect(text()).toContain('Não foi possível carregar o painel');
    expect(byTestId('planned-total')).toBeUndefined();

    const late = new Subject<DueDashboard>();
    reports.dueDashboard.mockReturnValueOnce(late).mockImplementation((month: string) => of(dashboard({ plannedTotal: '1.00' }, month)));
    fixture.componentInstance.goTo('2026-08');
    fixture.componentInstance.goTo('2026-09');
    late.next(dashboard({ plannedTotal: '999.00' }, '2026-08'));
    late.complete();
    fixture.detectChanges();
    expect(fixture.componentInstance.dashboard()?.month).toBe('2026-09');
    expect(byTestId('planned-total')).toBe('R$ 1,00');
  });

  it('shows the server message of a rejected filter', () => {
    reports.dueDashboard.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400,
      error: { code: 'REPORT_QUERY_INVALID', message: 'Informe o mês no formato AAAA-MM.', fieldErrors: [] } })));
    fixture.componentInstance.nextMonth();
    fixture.detectChanges();
    expect(text()).toContain('Informe o mês no formato AAAA-MM.');
  });
});

describe('report helpers', () => {
  it('format exact decimals without converting to a number', () => {
    expect(formatCurrency('199999999.99')).toBe('R$ 199.999.999,99');
    expect(formatCurrency('12345678901234567.89')).toBe('R$ 12.345.678.901.234.567,89');
    expect(formatCurrency('0.00')).toBe('R$ 0,00');
    expect(formatCurrency('-10.00')).toBe('-R$ 10,00');
    expect(formatSigned('5.00')).toBe('+R$ 5,00');
    expect(formatSigned('-0.00')).toBe('R$ 0,00');
  });

  it('compute months in the space time zone', () => {
    expect(currentMonth('America/Sao_Paulo', new Date('2026-11-01T02:30:00Z'))).toBe('2026-10');
    expect(currentMonth('UTC', new Date('2026-11-01T02:30:00Z'))).toBe('2026-11');
    expect(shiftMonth('2026-01', -1)).toBe('2025-12');
    expect(monthBounds('2028-02')).toEqual({ from: '2028-02-01', to: '2028-02-29' });
  });
});
