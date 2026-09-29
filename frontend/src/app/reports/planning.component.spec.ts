import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { CategoryService } from '../expenses/category.service';
import { ExpenseService } from '../expenses/expense.service';
import { PlanningComponent } from './planning.component';
import { Planning, PlanningItem, PlanningTotals, ReportService } from './report.service';

const totals = (overrides: Partial<PlanningTotals> = {}): PlanningTotals => ({
  count: 5, plannedTotal: '1583.33', confirmedTotal: '1373.33', estimatedTotal: '210.00', materializedCount: 2,
  materializedTotal: '1233.33', forecastCount: 3, forecastTotal: '350.00', paidCount: 1, paidTotal: '880.00',
  openCount: 4, openTotal: '683.33', oneOffTotal: '900.00', installmentTotal: '333.33', recurrenceTotal: '350.00',
  ...overrides,
});

const item = (overrides: Partial<PlanningItem>): PlanningItem => ({
  kind: 'EXPENSE', expenseId: 'e1', recurrenceId: null, origin: 'ONE_OFF', installment: null, description: 'IPVA',
  date: '2027-01-20', dueDate: '2027-01-20', amount: '900.00', estimated: false, status: 'PENDING', overdue: false,
  paidAmount: null, paymentDate: null, categoryName: null, responsibleDisplayName: null, ...overrides,
});

const planning = (month = '2026-10', page = 0, content: PlanningItem[] = [], totalPages = 1): Planning => ({
  horizonStart: '2026-10', horizonEnd: '2027-10', periodStart: '2026-10-01', periodEnd: '2027-10-31',
  dateBasis: 'DUE_DATE', today: '2026-10-15', timeZone: 'America/Sao_Paulo',
  totals: totals({ count: 39, plannedTotal: '6900.00', estimatedTotal: '1680.00', materializedTotal: '3770.00',
    forecastTotal: '3130.00', openTotal: '5900.00' }),
  months: [{ month: '2026-10', totals: totals({ plannedTotal: '310.00' }) }, { month: '2027-01', totals: totals() }],
  month, monthTotals: totals(), content, page, size: 20, totalElements: content.length, totalPages,
});

describe('PlanningComponent', () => {
  let fixture: ComponentFixture<PlanningComponent>;
  const reports = { planning: vi.fn() };
  const text = () => (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
  const byTestId = (id: string) => (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`)?.textContent?.trim();
  const button = (label: string) => Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
    .find(b => b.textContent!.trim() === label || b.getAttribute('aria-label') === label) as HTMLButtonElement;

  beforeEach(async () => {
    vi.clearAllMocks();
    reports.planning.mockImplementation((month: string | null) => of(planning(month ?? '2026-10', 0, [
      item({ description: 'Internet', kind: 'FORECAST', expenseId: null, recurrenceId: 'r1', origin: 'RECURRENCE',
        status: 'FORECAST', date: '2027-01-05', amount: '100.00' }),
      item({ description: 'Luz', kind: 'FORECAST', expenseId: null, recurrenceId: 'r2', origin: 'RECURRENCE',
        status: 'FORECAST', estimated: true, amount: '210.00' }),
      item({ status: 'PAID', paidAmount: '880.00', paymentDate: '2026-10-14' }),
      item({ expenseId: 'e2', description: 'Sofá', origin: 'INSTALLMENT', installment: { purchaseId: 'p', number: 2, count: 3 },
        overdue: true, categoryName: 'Casa', responsibleDisplayName: 'Bia' }),
    ])));
    await TestBed.configureTestingModule({
      imports: [PlanningComponent],
      providers: [provideRouter([]), { provide: ReportService, useValue: reports },
        { provide: ExpenseService, useValue: { filterOptions: () => of({ responsiblePeople: [{ userId: 'u1', displayName: 'Ana', activeMember: false }], payerPeople: [] }) } },
        { provide: CategoryService, useValue: { list: () => of([{ id: 'cat', name: 'Moradia', archived: true, version: 0, updatedAt: '' }]) } }],
    }).compileComponents();
    fixture = TestBed.createComponent(PlanningComponent);
    fixture.detectChanges();
  });

  it('opens the current month of the server horizon and explains the basis and composition', () => {
    expect(reports.planning).toHaveBeenCalledWith(null, {}, 0, 20);
    expect(text()).toContain('Base temporal: vencimento');
    expect(text()).toContain('Outubro de 2026 a Outubro de 2027');
    expect(byTestId('planning-total')).toBe('R$ 6.900,00');
    expect(byTestId('planning-materialized')).toContain('R$ 3.770,00');
    expect(byTestId('planning-forecast')).toContain('R$ 3.130,00');
    expect(byTestId('planning-estimated')).toContain('R$ 1.680,00');
    expect(byTestId('planning-open')).toContain('R$ 5.900,00');
    expect(text()).toContain('Não mostra receitas, saldo nem orçamento');
    expect(text()).toContain('Moradia (arquivada)');
    expect(text()).toContain('Ana (ex-membro)');
  });

  it('identifies forecasts, estimates, installments, payments and overdue entries', () => {
    const rows = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="planning-item"]'));
    expect(rows).toHaveLength(4);
    expect(rows[0].textContent).toContain('Previsão');
    expect(rows[0].textContent).toContain('Previsão (ainda não gerada)');
    expect(rows[1].textContent).toContain('a confirmar');
    expect(rows[2].textContent).toContain('Lançamento');
    expect(rows[2].textContent).toContain('Paga em 14/10/2026 por R$ 880,00');
    expect(rows[3].textContent).toContain('Parcela 2/3');
    expect(rows[3].textContent).toContain('Casa');
    expect(rows[3].textContent).toContain('Bia');
    expect(rows[3].textContent).toContain('Pendente, atrasada');
    expect(rows[3].classList).toContain('overdue');
    expect(text()).toMatch(/Avulsas R\$\s900,00 · parcelas R\$\s333,33 · recorrências R\$\s350,00/);
  });

  it('selects another month of the horizon and applies filters to every value from the first page', () => {
    button('Ver Janeiro de 2027').click();
    fixture.detectChanges();
    expect(reports.planning).toHaveBeenLastCalledWith('2027-01', {}, 0, 20);
    expect(byTestId('planning-month-title')).toContain('Janeiro de 2027');
    fixture.componentInstance.filterForm.setValue({ search: ' luz ', category: '__none__', responsible: 'u1' });
    fixture.componentInstance.applyFilters();
    fixture.detectChanges();
    expect(reports.planning).toHaveBeenLastCalledWith('2027-01',
      { search: 'luz', withoutCategory: true, responsibleUserId: 'u1' }, 0, 20);
    expect(text()).toContain('Filtros aplicados a todos os valores.');
    fixture.componentInstance.clearFilters();
    expect(reports.planning).toHaveBeenLastCalledWith('2027-01', {}, 0, 20);
  });

  it('pages the month without losing the totals and shows the empty state', () => {
    reports.planning.mockReturnValue(of(planning('2026-10', 0, [item({})], 2)));
    fixture.componentInstance.reload();
    fixture.detectChanges();
    button('Próxima').click();
    expect(reports.planning).toHaveBeenLastCalledWith('2026-10', {}, 1, 20);
    fixture.componentInstance.previousPage();
    expect(reports.planning).toHaveBeenLastCalledWith('2026-10', {}, 0, 20);
    reports.planning.mockReturnValue(of(planning('2026-10', 0, [])));
    fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(text()).toContain('Nenhuma despesa nem previsão neste mês com os filtros escolhidos.');
  });

  it('shows the server message on error, never stale numbers, and ignores outdated responses', () => {
    reports.planning.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400,
      error: { message: 'Escolha um mês entre 10/2026 e 10/2027, o horizonte do planejamento.' } })));
    fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(text()).toContain('Escolha um mês entre 10/2026 e 10/2027');
    expect(byTestId('planning-total')).toBeUndefined();
    reports.planning.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    fixture.componentInstance.reload();
    fixture.detectChanges();
    expect(text()).toContain('Verifique a conexão');

    const slow = new Subject<Planning>();
    reports.planning.mockReturnValueOnce(slow).mockReturnValueOnce(of(planning('2027-01')));
    fixture.componentInstance.selectMonth('2026-10');
    fixture.componentInstance.selectMonth('2027-01');
    slow.next(planning('2026-10'));
    fixture.detectChanges();
    expect(fixture.componentInstance.planning()!.month).toBe('2027-01');
  });

  it('shows the loading state before the first answer', async () => {
    reports.planning.mockReturnValue(new Subject<Planning>());
    const pending = TestBed.createComponent(PlanningComponent);
    pending.detectChanges();
    expect((pending.nativeElement as HTMLElement).textContent).toContain('Carregando o planejamento');
  });
});
