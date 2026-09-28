import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { CategoryService } from '../expenses/category.service';
import { AccountAccessService } from '../identity/account-access.service';
import { RecurrenceComponent } from './recurrence.component';
import { RecurrenceService } from './recurrence.service';

describe('RecurrenceComponent', () => {
  let fixture: ComponentFixture<RecurrenceComponent>;
  const api = { newIdempotencyKey: vi.fn(() => 'key'), list: vi.fn(() => of([])),
    forecasts: vi.fn(() => of({ from: '2026-09', to: '2027-09', occurrences: [] })),
    preview: vi.fn(() => of(['2027-01-31', '2027-02-28', '2027-03-31'])),
    create: vi.fn(() => of({ previewDates: ['2027-01-31', '2027-02-28'] })),
    confirmForecastCharge: vi.fn(),
    anticipate: vi.fn(item => of({ occurrence: { ...item, state: 'MATERIALIZED', expenseId: 'expense-1' }, replayed: false })) };
  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({ imports: [RecurrenceComponent], providers: [provideRouter([]),
      { provide: RecurrenceService, useValue: api }, { provide: CategoryService, useValue: { list: () => of([]) } },
      { provide: AccountAccessService, useValue: { members: () => of([]) } }] }).compileComponents();
    fixture = TestBed.createComponent(RecurrenceComponent);
    fixture.detectChanges();
  });

  it('previews short months through the server and explains current-month materialization', () => {
    fixture.componentInstance.form.patchValue({ description: 'Condomínio', amount: '500,00',
      firstDueDate: '2027-01-31' });
    fixture.componentInstance.preview();
    expect(api.preview).toHaveBeenCalledWith({ firstDueDate: '2027-01-31', lastDueDate: null, frequency: 'MONTHLY' });
    expect(fixture.componentInstance.previewDates()).toContain('2027-03-31');
    fixture.componentInstance.submit();
    expect(api.create).toHaveBeenCalled();
    expect(fixture.componentInstance.message()).toContain('em até 30 segundos');
  });

  it('requires confirmation and replaces a forecast after anticipation', () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const item = { recurrenceId: 'rec-1', description: 'Seguro', amount: '100.00', estimated: false,
      scheduledDueDate: '2026-10-03', state: 'FORECAST' as const, expenseId: null, actualDueDate: null,
      expenseStatus: null, chargeConfirmed: false };
    fixture.componentInstance.anticipate(item);
    expect(api.anticipate).toHaveBeenCalledWith(item, 'key');
    expect(fixture.componentInstance.anticipatedExpenseId()).toBe('expense-1');
    expect(api.forecasts).toHaveBeenCalledTimes(2);
  });

  it('confirms an estimated forecast, preserving the typed value on conflict', () => {
    const component = fixture.componentInstance;
    const item = { recurrenceId: 'rec-1', description: 'Energia', amount: '180.00', estimated: true,
      scheduledDueDate: '2026-11-10', state: 'FORECAST' as const, expenseId: null, actualDueDate: null,
      expenseStatus: null, chargeConfirmed: false };
    expect(component.canConfirm(item)).toBe(true);
    expect(component.canConfirm({ ...item, estimated: false })).toBe(false);
    expect(component.canConfirm({ ...item, state: 'MATERIALIZED' })).toBe(false);
    component.openConfirm(item);
    fixture.detectChanges();
    component.confirmAmount.setValue('0');
    component.confirmCharge();
    expect(api.confirmForecastCharge).not.toHaveBeenCalled();
    component.confirmAmount.setValue('205,40');
    api.confirmForecastCharge.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409,
      error: { message: 'O valor desta cobrança já foi confirmado.' } })));
    component.confirmCharge();
    expect(api.confirmForecastCharge).toHaveBeenCalledWith(item, '205.40', 'key');
    expect(component.confirmError()).toContain('já foi confirmado');
    expect(component.confirmAmount.value).toBe('205,40');
    api.confirmForecastCharge.mockReturnValueOnce(of({ occurrence: { ...item, state: 'MATERIALIZED', amount: '205.40',
      expenseId: 'expense-9', chargeConfirmed: true }, replayed: false }));
    component.confirmCharge();
    expect(component.confirmingForecast()).toBeNull();
    expect(component.anticipatedExpenseId()).toBe('expense-9');
    expect(component.message()).toContain('pendente de quitação');
  });

  it('reloads after a change or conflict and describes history, closure and review flags', () => {
    const component = fixture.componentInstance;
    const recurrence = { id: 'rec-1', description: 'Gás', amount: '80.00', valueType: 'VARIABLE_ESTIMATE' as const,
      frequency: 'MONTHLY' as const, firstDueDate: '2026-09-15', lastDueDate: '2026-10-15', categoryId: null, responsibleUserId: null,
      baseDay: 15, categoryName: null, responsibleDisplayName: null, createdByDisplayName: 'Ana', createdAt: '2026-09-01T10:00:00Z',
      version: 1, previewDates: [], upcomingDates: ['2026-09-15', '2026-10-15'], closedAt: '2026-09-27T12:00:00Z',
      closedByDisplayName: 'Convidado', closureReason: 'Mudança', segments: [], changes: [] };
    const closure = { id: 'c-1', type: 'CLOSURE' as const, actorUserId: 'u', actorDisplayName: 'Convidado', occurredAt: '2026-09-27T12:00:00Z',
      version: 1, effectiveDueDate: '2026-10-15', changedFields: ['lastDueDate'], reason: 'Mudança', updatedCount: 0, removedCount: 1,
      reviewCount: 2, preservedCount: 0 };
    (api.list as ReturnType<typeof vi.fn>).mockReturnValue(of([{ ...recurrence, changes: [closure] }]) as never);
    component.changed({ recurrence, change: closure, replayed: false });
    expect(component.message()).toContain('encerrada');
    expect(api.list).toHaveBeenCalledTimes(2);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Encerrada: último vencimento 2026-10-15');
    expect(component.changeLabel(closure)).toContain('Motivo: Mudança');
    const change = { ...closure, type: 'CHANGE' as const, changedFields: ['amount', 'dueDay'], reason: null, updatedCount: 3 };
    expect(component.changeLabel(change)).toContain('valor, dia de vencimento');
    expect(component.changeLabel(change)).toContain('3 atualizado(s)');
    component.changed({ recurrence, change, replayed: false });
    expect(component.message()).toContain('alterada a partir de 2026-10-15');
    component.refresh();
    expect(api.forecasts).toHaveBeenCalledTimes(4);
    const item = { recurrenceId: 'rec-1', description: 'Gás', amount: '85.00', estimated: false, scheduledDueDate: '2026-11-15',
      state: 'MATERIALIZED' as const, expenseId: 'e', actualDueDate: '2026-11-15', expenseStatus: 'PAID', chargeConfirmed: true,
      reviewReason: 'AFTER_END' as const };
    expect(component.reviewLabel(item)).toBe('Revisar: depois do término');
    expect(component.reviewLabel({ ...item, reviewReason: 'OUTSIDE_SCHEDULE' })).toContain('fora da nova programação');
    expect(component.reviewLabel({ ...item, reviewReason: null })).toBeNull();
  });
  it('submits the forecast confirmation through the form instead of reloading the page', () => {
    const component = fixture.componentInstance;
    const item = { recurrenceId: 'rec-1', description: 'Energia', amount: '180.00', estimated: true,
      scheduledDueDate: '2026-11-10', state: 'FORECAST' as const, expenseId: null, actualDueDate: null,
      expenseStatus: null, chargeConfirmed: false };
    api.confirmForecastCharge.mockReturnValueOnce(of({ occurrence: { ...item, state: 'MATERIALIZED', amount: '201.00',
      expenseId: 'expense-7', chargeConfirmed: true }, replayed: false }));
    component.forecasts.set([item]);
    component.openConfirm(item);
    fixture.detectChanges();
    const form = fixture.nativeElement.querySelector('form.forecast-confirm') as HTMLFormElement;
    const input = form.querySelector('input') as HTMLInputElement;
    input.value = '201,00';
    input.dispatchEvent(new Event('input'));
    const submit = new Event('submit', { cancelable: true });
    form.dispatchEvent(submit);
    expect(submit.defaultPrevented).toBe(true);
    expect(api.confirmForecastCharge).toHaveBeenCalledWith(item, '201.00', 'key');
    expect(component.message()).toContain('confirmado em R$ 201.00');
  });
});
