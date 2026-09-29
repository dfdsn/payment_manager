import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AccountAccessService } from '../identity/account-access.service';
import { MonthClosingComponent } from './month-closing.component';
import { ClosingLine, ClosingSnapshot, MonthClosing, ReportService } from './report.service';

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
  timeZone: 'America/Sao_Paulo', closable: true, status: 'NOT_CLOSED', changes: [], saved: null, current: snapshot(),
  ...overrides,
});

const closed = (): MonthClosing => open('2026-10', {
  status: 'UP_TO_DATE',
  saved: snapshot({ version: 1, authorUserId: 'u2', authorDisplayName: 'Convidado', closedAt: '2026-10-15T15:00:00Z', pendingAcknowledged: true }),
});

describe('MonthClosingComponent', () => {
  let fixture: ComponentFixture<MonthClosingComponent>;
  const reports = { closing: vi.fn(), closeMonth: vi.fn(), closings: vi.fn(), closingVersions: vi.fn(),
    closingVersion: vi.fn(), generateClosingVersion: vi.fn() };
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
    reports.closings.mockImplementation((year: number) => of({ year, closings: [] }));
    reports.closingVersions.mockImplementation((month: string) => of({ month, currentVersion: 1, versions: [
      { version: 1, authorUserId: 'u2', authorDisplayName: 'Convidado', closedAt: '2026-10-15T15:00:00Z', businessDate: '2026-10-15',
        pendingAcknowledged: true, indicators: indicators(), current: true }] }));
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

  it('flags a closing whose current data differ and lists each difference with saved and current values', async () => {
    const saved = closed().saved!;
    const lines = saved.lines;
    const paidInternet: ClosingLine = { ...lines[0], status: 'PAID', paidAmount: '1500.00', adjustment: '0.00', overdue: false };
    const gift: ClosingLine = { ...lines[2], expenseId: 'e9', description: 'Presente', chargeAmount: '50.00', estimated: false, referenceDate: '2026-10-25' };
    reports.closing.mockReturnValue(of(open('2026-10', {
      status: 'OUTDATED', saved,
      current: snapshot({ contentDigest: 'd2', indicators: indicators({ paidTotal: '1610.00', pendingTotal: '230.00' }) }),
      changes: [
        { kind: 'ADDED', expenseId: 'e9', fields: [], saved: null, current: gift },
        { kind: 'CHANGED', expenseId: 'e1', fields: ['SITUATION', 'PAID_AMOUNT'], saved: lines[0], current: paidInternet },
        { kind: 'REMOVED', expenseId: 'e3', fields: [], saved: lines[2], current: null },
        { kind: 'CHANGED', expenseId: 'e2', fields: ['REFERENCE_DATE', 'CHARGE', 'ESTIMATE', 'CATEGORY'], saved: lines[1],
          current: { ...lines[1], referenceDate: '2026-10-07', chargeAmount: '130.00', estimated: true, categoryId: null, categoryName: null } },
      ],
    })));
    reports.closings.mockReturnValue(of({ year: 2026, closings: [
      { month: '2026-09', version: 1, authorDisplayName: 'Admin', closedAt: '2026-10-01T13:00:00Z', status: 'UP_TO_DATE' },
      { month: '2026-10', version: 1, authorDisplayName: 'Convidado', closedAt: '2026-10-15T15:00:00Z', status: 'OUTDATED' },
    ] }));
    fixture.componentInstance.reload();
    fixture.detectChanges();

    expect(byTestId('closing-status')!.classList).toContain('outdated');
    expect(byTestId('closing-outdated')!.textContent).toContain('4 diferença(s)');
    expect(byTestId('closing-up-to-date')).toBeNull();
    const changes = byTestId('closing-changes')!.textContent!.replace(/\s+/g, ' ');
    expect(changes).toContain('Presente · Entrou no mês depois do fechamento');
    expect(changes).toContain('Situação: Pendente → Paga');
    expect(changes).toContain('Valor pago: — → R$ 1.500,00');
    expect(changes).toContain('Luz · Saiu do mês: cancelada ou com data em outro mês');
    expect(changes).toContain('no retrato: R$ 180,00, pendente');
    expect(changes).toContain('Vencimento: 05/10/2026 → 07/10/2026');
    expect(changes).toContain('Cobrança: R$ 120,00 → R$ 130,00');
    expect(changes).toContain('Estimativa: confirmado → a confirmar');
    expect(changes).toContain('Categoria: Casa e contas → Sem categoria');
    // Saved and current side by side, each with its own numbers.
    expect(text()).toContain('Retrato salvo (versão 1, vigente)');
    expect(byTestId('saved-paid')!.textContent).toContain('R$ 110,00');
    expect(byTestId('current-paid')!.textContent).toContain('R$ 1.610,00');
    expect(byTestId('closing-item-2026-10')!.textContent).toContain('Alterado depois');
    expect(byTestId('closing-item-2026-09')!.textContent).toContain('Atualizado');
  });

  it('shows an up-to-date closing and opens a month from the annual list', async () => {
    reports.closing.mockImplementation((month: string) => of(month === '2026-10' ? closed() : open(month)));
    reports.closings.mockReturnValue(of({ year: 2026, closings: [
      { month: '2026-09', version: 1, authorDisplayName: 'Admin', closedAt: '2026-10-01T13:00:00Z', status: 'UP_TO_DATE' },
    ] }));
    fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(byTestId('closing-status')!.classList).toContain('closed');
    expect(byTestId('closing-up-to-date')!.textContent).toContain('mesmos valores e classificações');
    expect(byTestId('closing-changes')).toBeNull();
    expect(byTestId('closings-list')!.textContent).toContain('Admin');
    (byTestId('closings-list')!.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(reports.closing).toHaveBeenLastCalledWith('2026-09');
    expect(reports.closings).toHaveBeenLastCalledWith(2026);
    reports.closings.mockReturnValue(of({ year: 2025, closings: [] }));
    fixture.componentInstance.goTo('2025-12');
    fixture.detectChanges();
    expect(byTestId('closings-empty')!.textContent).toContain('Nenhum mês de 2025');
  });

  it('generates a new version from the version in force and lets an earlier version be read as saved', async () => {
    const v1 = closed().saved!;
    const v2 = snapshot({ version: 2, authorUserId: 'u1', authorDisplayName: 'Diego', closedAt: '2026-10-16T13:00:00Z',
      businessDate: '2026-10-16', pendingAcknowledged: true, contentDigest: 'd2', indicators: indicators({ paidTotal: '1610.00', pendingTotal: '180.00' }) });
    reports.closing.mockReturnValue(of(open('2026-10', { status: 'OUTDATED', saved: v1, changes: [
      { kind: 'CHANGED', expenseId: 'e1', fields: ['SITUATION', 'PAID_AMOUNT'], saved: v1.lines[0],
        current: { ...v1.lines[0], status: 'PAID', paidAmount: '1500.00', adjustment: '0.00', overdue: false } }] })));
    reports.generateClosingVersion.mockReturnValue(of(open('2026-10', { status: 'UP_TO_DATE', saved: v2 })));
    fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(byTestId('version-1')!.textContent).toContain('Vigente');

    await click('generate-version');
    expect(text()).toContain('Gerar a versão 2 de Outubro de 2026');
    expect(text()).toContain('A versão 1 e as anteriores continuam salvas');
    expect(byTestId('no-changes-warning')).toBeNull();
    expect((byTestId('confirm-close') as HTMLButtonElement).disabled).toBe(true);
    (byTestId('acknowledge-pending') as HTMLInputElement).click();
    fixture.detectChanges();
    reports.closingVersions.mockReturnValue(of({ month: '2026-10', currentVersion: 2, versions: [
      { version: 1, authorUserId: 'u2', authorDisplayName: 'Convidado', closedAt: '2026-10-15T15:00:00Z', businessDate: '2026-10-15',
        pendingAcknowledged: true, indicators: indicators(), current: false },
      { version: 2, authorUserId: 'u1', authorDisplayName: 'Diego', closedAt: '2026-10-16T13:00:00Z', businessDate: '2026-10-16',
        pendingAcknowledged: true, indicators: v2.indicators, current: true }] }));
    await click('confirm-close');

    expect(reports.generateClosingVersion).toHaveBeenCalledWith('2026-10', 1, true, expect.stringMatching(/^[0-9a-f-]{36}$/));
    expect(byTestId('closing-success')!.textContent).toContain('Versão 2 de Outubro de 2026 gerada');
    expect(byTestId('closing-status')!.textContent).toContain('versão 2 (vigente)');
    expect(byTestId('closing-up-to-date')).not.toBeNull();
    expect(byTestId('version-2')!.textContent).toContain('Vigente');
    expect(byTestId('version-1')!.textContent).toContain('Convidado');

    reports.closingVersion.mockReturnValue(of({ month: '2026-10', currentVersion: 2, current: false, snapshot: v1 }));
    (byTestId('version-1')!.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(reports.closingVersion).toHaveBeenCalledWith('2026-10', 1);
    expect(byTestId('old-version')!.textContent).toContain('Versão 1 — anterior');
    expect(byTestId('old-version')!.textContent).toContain('A versão vigente é a 2');
    expect(byTestId('version-paid')!.textContent).toContain('R$ 110,00');
    expect(byTestId('saved-paid')!.textContent).toContain('R$ 1.610,00');
    fixture.componentInstance.hideVersion();
    fixture.detectChanges();
    expect(byTestId('old-version')).toBeNull();
  });

  it('warns that nothing changed and reloads when another member generated a version first', async () => {
    reports.closing.mockReturnValue(of(closed()));
    fixture.componentInstance.reload();
    fixture.detectChanges();
    await click('generate-version');
    expect(byTestId('no-changes-warning')!.textContent).toContain('Não há diferenças de valores ou classificações desde a versão 1');
    (byTestId('acknowledge-pending') as HTMLInputElement).click();
    fixture.detectChanges();
    reports.generateClosingVersion.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409,
      error: { code: 'CLOSING_VERSION_CONFLICT', message: 'Outra versão foi gerada enquanto você revisava (versão vigente: 2).' } })));
    await click('confirm-close');
    fixture.detectChanges();
    expect(text()).toContain('Outra versão foi gerada');
    expect(byTestId('confirm-panel')).toBeNull();
    expect(reports.closing).toHaveBeenLastCalledWith('2026-10');
  });
});
