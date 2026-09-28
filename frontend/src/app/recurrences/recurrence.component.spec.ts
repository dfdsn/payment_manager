import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
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
});
