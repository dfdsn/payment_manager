import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AccountAccessService } from '../identity/account-access.service';
import { MonthClosingComponent } from './month-closing.component';
import { ClosingSnapshot, MonthClosing, ReportService } from './report.service';

const indicators = (overrides: Partial<ClosingSnapshot['indicators']> = {}) => ({
  plannedCount: 3, plannedTotal: '1790.00', plannedEstimated: '180.00', paidCount: 1, paidTotal: '110.00',
  pendingCount: 2, pendingTotal: '1680.00', pendingEstimated: '180.00', overdueCount: 1, overdueTotal: '1500.00',
  overdueEstimated: '0.00', adjustmentIncrease: '0.00', adjustmentDiscount: '10.00', adjustmentNet: '-10.00', ...overrides,
});

const snapshot = (overrides: Partial<ClosingSnapshot> = {}): ClosingSnapshot => ({
  version: null, authorUserId: null, authorDisplayName: null, closedAt: null, businessDate: '2026-10-15',
  timeZone: 'America/Sao_Paulo', pendingAcknowledged: false, contentDigest: 'd1', indicators: indicators(),
  categories: [
    { categoryId: 'c1', categoryName: 'Casa e contas', count: 2, plannedTotal: '1620.00', plannedEstimated: '0.00', paidTotal: '110.00', pendingCount: 1, pendingTotal: '1500.00' },
    { categoryId: null, categoryName: null, count: 1, plannedTotal: '180.00', plannedEstimated: '180.00', paidTotal: '0.00', pendingCount: 1, pendingTotal: '180.00' },
  ],
  lines: [
    { expenseId: 'e1', description: 'Aluguel', origin: 'ONE_OFF', installmentNumber: null, installmentCount: null, referenceDate: '2026-10-10', dueDateInformed: true, status: 'PENDING', chargeAmount: '1500.00', estimated: false, paidAmount: null, adjustment: null, overdue: true, categoryId: 'c1', categoryName: 'Casa e contas' },
    { expenseId: 'e2', description: 'Telefone', origin: 'INSTALLMENT', installmentNumber: 1, installmentCount: 3, referenceDate: '2026-10-05', dueDateInformed: false, status: 'PAID', chargeAmount: '120.00', estimated: false, paidAmount: '110.00', adjustment: '-10.00', overdue: false, categoryId: 'c1', categoryName: 'Casa e contas' },
    { expenseId: 'e3', description: 'Luz', origin: 'RECURRENCE', installmentNumber: null, installmentCount: null, referenceDate: '2026-10-20', dueDateInformed: true, status: 'PENDING', chargeAmount: '180.00', estimated: true, paidAmount: null, adjustment: null, overdue: false, categoryId: null, categoryName: null },
  ],
  ...overrides,
});

const open = (month = '2026-10', overrides: Partial<MonthClosing> = {}): MonthClosing => ({
  month, periodStart: `${month}-01`, periodEnd: `${month}-31`, dateBasis: 'DUE_DATE', today: '2026-10-15',
  timeZone: 'America/Sao_Paulo', closable: true, saved: null, current: snapshot(), ...overrides,
});

const closed = (): MonthClosing => open('2026-10', {
  saved: snapshot({ version: 1, authorUserId: 'u2', authorDisplayName: 'Convidado', closedAt: '2026-10-15T15:00:00Z', pendingAcknowledged: true }),
});

describe('MonthClosingComponent', () => {
  let fixture: ComponentFixture<MonthClosingComponent>;
  const reports = { closing: vi.fn(), closeMonth: vi.fn() };
  const element = () => fixture.nativeElement as HTMLElement;
  const text = () => element().textContent!.replace(/\s+/g, ' ');
  const byTestId = (id: string) => element().querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const click = async (id: string) => { byTestId(id)!.click(); fixture.detectChanges(); await fixture.whenStable(); };

  beforeEach(async () => {
    vi.clearAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    // 02:30 UTC on 1 November is still 31 October in São Paulo: the current month is October.
    vi.setSystemTime(new Date('2026-11-01T02:30:00Z'));
    reports.closing.mockImplementation((month: string) => of(open(month)));
    await TestBed.configureTestingModule({
      imports: [MonthClosingComponent],
      providers: [provideRouter([]), { provide: ReportService, useValue: reports },
        { provide: AccountAccessService, useValue: { context: () => of({ userId: 'u1', timeZone: 'America/Sao_Paulo' }) } }],
    }).compileComponents();
    fixture = TestBed.createComponent(MonthClosingComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  afterEach(() => vi.useRealTimers());

  it('opens the current month of the space and shows its current data before closing', () => {
    expect(reports.closing).toHaveBeenCalledWith('2026-10');
    expect(text()).toContain('Outubro de 2026');
    expect(text()).toContain('Mês não fechado');
    expect(text()).toContain('Base temporal: vencimento');
    expect(byTestId('current-planned')!.textContent).toContain('R$ 1.790,00');
    expect(byTestId('current-pending')!.textContent).toContain('R$ 1.680,00');
    expect(text()).toContain('Sem categoria');
    expect(byTestId('current-pending-list')!.textContent).toContain('Aluguel');
    expect(byTestId('current-pending-list')!.textContent).toContain('atrasada');
    expect(byTestId('current-pending-list')!.textContent).not.toContain('Telefone');
    expect(byTestId('current-lines')!.textContent).toContain('Parcela 1/3');
    expect(byTestId('current-lines')!.textContent).toContain('(pagamento)');
  });

  it('requires acknowledging pending entries and sends the confirmation with a key', async () => {
    reports.closeMonth.mockReturnValue(of(closed()));
    await click('close-month');
    expect(byTestId('pending-warning')!.textContent).toContain('Há 2 conta(s) pendente(s)');
    expect(byTestId('pending-warning')!.textContent).toContain('não interrompe lembretes');
    expect((byTestId('confirm-close') as HTMLButtonElement).disabled).toBe(true);
    const checkbox = byTestId('acknowledge-pending') as HTMLInputElement;
    checkbox.click();
    fixture.detectChanges();
    expect((byTestId('confirm-close') as HTMLButtonElement).disabled).toBe(false);
    await click('confirm-close');
    expect(reports.closeMonth).toHaveBeenCalledWith('2026-10', true, expect.stringMatching(/^[0-9a-f-]{36}$/));
    expect(byTestId('closing-success')!.textContent).toContain('Outubro de 2026 fechado');
    expect(byTestId('closing-status')!.textContent).toContain('Mês fechado');
    expect(byTestId('closing-status')!.textContent).toContain('versão 1');
    expect(byTestId('closing-status')!.textContent).toContain('Convidado');
    expect(byTestId('closing-status')!.textContent).toMatch(/15\/10\/2026,? 12:00/);
    expect(byTestId('saved-planned')!.textContent).toContain('R$ 1.790,00');
    expect(byTestId('close-month')).toBeNull();
  });

  it('reuses the same key after a connection failure and a new key for a new confirmation', async () => {
    reports.closeMonth.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })))
      .mockReturnValueOnce(of(closed()));
    await click('close-month');
    (byTestId('acknowledge-pending') as HTMLInputElement).click();
    fixture.detectChanges();
    await click('confirm-close');
    expect(text()).toContain('Sem conexão com o servidor');
    await click('confirm-close');
    const [first, second] = reports.closeMonth.mock.calls.map(call => call[2]);
    expect(second).toBe(first);
  });

  it('reloads the month when it was closed by the other member meanwhile', async () => {
    reports.closeMonth.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409,
      error: { code: 'MONTH_ALREADY_CLOSED', message: 'Este mês já foi fechado. Consulte o retrato salvo.' } })));
    reports.closing.mockReturnValue(of(closed()));
    await click('close-month');
    (byTestId('acknowledge-pending') as HTMLInputElement).click();
    fixture.detectChanges();
    await click('confirm-close');
    fixture.detectChanges();
    expect(text()).toContain('Este mês já foi fechado');
    expect(byTestId('closing-status')!.textContent).toContain('Mês fechado');
    expect(byTestId('confirm-panel')).toBeNull();
  });

  it('warns about an empty month and closes it without the pending checkbox', async () => {
    reports.closing.mockReturnValue(of(open('2026-08', { current: snapshot({ indicators: indicators({ plannedCount: 0,
      plannedTotal: '0.00', plannedEstimated: '0.00', paidCount: 0, paidTotal: '0.00', pendingCount: 0, pendingTotal: '0.00',
      pendingEstimated: '0.00', overdueCount: 0, overdueTotal: '0.00', adjustmentDiscount: '0.00', adjustmentNet: '0.00' }),
      categories: [], lines: [] }) })));
    reports.closeMonth.mockReturnValue(of(closed()));
    fixture.componentInstance.goTo('2026-08');
    fixture.detectChanges();
    await click('close-month');
    expect(byTestId('empty-warning')!.textContent).toContain('Não há lançamentos neste mês');
    expect(byTestId('pending-warning')).toBeNull();
    expect(text()).toContain('Nenhum lançamento neste mês');
    await click('confirm-close');
    expect(reports.closeMonth).toHaveBeenCalledWith('2026-08', false, expect.any(String));
  });

  it('does not offer to close a future month and never keeps another month on screen after an error', async () => {
    reports.closing.mockReturnValueOnce(of(open('2026-11', { closable: false })));
    fixture.componentInstance.nextMonth();
    fixture.detectChanges();
    expect(byTestId('closing-not-allowed')!.textContent).toContain('mês atual ou meses anteriores');
    expect(byTestId('close-month')).toBeNull();
    reports.closing.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
    fixture.componentInstance.nextMonth();
    fixture.detectChanges();
    expect(text()).toContain('Não foi possível carregar o fechamento');
    expect(byTestId('current-planned')).toBeNull();
  });
});
