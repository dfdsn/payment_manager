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
});
