import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { RecurrenceEditComponent } from './recurrence-edit.component';
import { Recurrence, RecurrenceImpact, RecurrenceService } from './recurrence.service';

describe('RecurrenceEditComponent', () => {
  let fixture: ComponentFixture<RecurrenceEditComponent>;
  let component: RecurrenceEditComponent;
  const recurrence: Recurrence = { id: 'rec-1', description: 'Energia', amount: '180.00', valueType: 'VARIABLE_ESTIMATE',
    frequency: 'MONTHLY', firstDueDate: '2026-09-05', lastDueDate: '2027-03-05', categoryId: null, responsibleUserId: null, baseDay: 5,
    categoryName: null, responsibleDisplayName: null, createdByDisplayName: 'Ana', createdAt: '2026-09-01T10:00:00Z', version: 2,
    previewDates: [], upcomingDates: ['2026-09-05', '2026-10-05', '2026-11-20', '2027-03-20'], closedAt: null, changes: [],
    segments: [
      { effectiveMonth: '2026-09-01', description: 'Energia', amount: '180.00', frequency: 'MONTHLY', dueDay: 5, categoryId: null,
        categoryName: null, responsibleUserId: null, responsibleDisplayName: null },
      { effectiveMonth: '2026-11-01', description: 'Energia casa', amount: '200.00', frequency: 'MONTHLY', dueDay: 20, categoryId: 'cat-1',
        categoryName: 'Casa', responsibleUserId: 'user-2', responsibleDisplayName: 'Beto' }] };
  const impact: RecurrenceImpact = { recurrenceId: 'rec-1', operation: 'CHANGE', version: 2, effectiveDueDate: '2026-10-05',
    changedFields: ['amount'], impactToken: 'token-1', updatedCount: 1, removedCount: 0, reviewCount: 1, preservedCount: 1,
    occurrences: [{ expenseId: 'e-1', scheduledDueDate: '2026-10-05', dueDate: '2026-10-05', description: 'Energia', amount: '195.00',
      status: 'PENDING', chargeConfirmed: true, action: 'PRESERVE', reason: 'UNCHANGED', changes: [], preservedFields: ['amount'] }],
    forecasts: [{ month: '2027-01-01', action: 'CHANGED', previousDueDate: '2027-01-20', newDueDate: '2027-01-20', previousAmount: '200.00',
      newAmount: '210.00', previousDescription: 'Energia casa', newDescription: 'Energia casa' }] };
  const api = { newIdempotencyKey: vi.fn(() => 'key-1'), previewChange: vi.fn(), applyChange: vi.fn(), previewClosure: vi.fn(),
    applyClosure: vi.fn() };

  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({ imports: [RecurrenceEditComponent],
      providers: [{ provide: RecurrenceService, useValue: api }] }).compileComponents();
    fixture = TestBed.createComponent(RecurrenceEditComponent);
    fixture.componentRef.setInput('recurrence', recurrence);
    fixture.componentRef.setInput('categories', [{ id: 'cat-1', name: 'Casa' }]);
    fixture.componentRef.setInput('members', [{ userId: 'user-2', displayName: 'Beto' }]);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('fills the configuration in force for the chosen period and explains scope and preservation', () => {
    component.openChange();
    fixture.detectChanges();
    expect(component.changeForm.getRawValue()).toMatchObject({ effectiveDueDate: '2026-09-05', description: 'Energia', amount: '180.00', dueDay: 5 });
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Alterar este e os próximos');
    expect(text).toContain('Valores confirmados e vencimentos corrigidos');
    expect(text).toContain('Estimativa');
    component.changeForm.controls.effectiveDueDate.setValue('2026-11-20');
    component.startChanged();
    expect(component.changeForm.getRawValue()).toMatchObject({ description: 'Energia casa', amount: '200.00', dueDay: 20,
      categoryId: 'cat-1', responsibleUserId: 'user-2' });
  });

  it('reviews the impact, applies exactly the reviewed request with its token and reports the result', () => {
    component.openChange();
    component.changeForm.patchValue({ effectiveDueDate: '2026-10-05', amount: '0' });
    component.review();
    expect(api.previewChange).not.toHaveBeenCalled();
    component.changeForm.patchValue({ amount: '210,00' });
    api.previewChange.mockReturnValue(of(impact));
    component.review();
    expect(api.previewChange).toHaveBeenCalledWith('rec-1', { version: 2, effectiveDueDate: '2026-10-05', description: 'Energia',
      amount: '210.00', frequency: 'MONTHLY', dueDay: 5, categoryId: null, responsibleUserId: null });
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Vale a partir de 2026-10-05');
    expect(text).toContain('Preservado: estimativa');
    expect(text).toContain('1 para revisão');
    const applied = vi.fn();
    component.applied.subscribe(applied);
    api.applyChange.mockReturnValue(of({ recurrence, replayed: false, change: { type: 'CHANGE', updatedCount: 1, removedCount: 0,
      reviewCount: 1, effectiveDueDate: '2026-10-05' } }));
    component.changeForm.patchValue({ amount: '999' });
    component.confirm();
    expect(api.applyChange).toHaveBeenCalledWith('rec-1', expect.objectContaining({ amount: '210.00', version: 2,
      impactToken: 'token-1' }), 'key-1');
    expect(applied).toHaveBeenCalled();
    expect(component.mode()).toBeNull();
    expect(component.message()).toContain('1 lançamento(s) atualizado(s)');
  });

  it('keeps typed data on a conflict and asks for a new review without applying anything else', () => {
    component.openChange();
    component.changeForm.patchValue({ effectiveDueDate: '2026-10-05', description: 'Luz nova', amount: '210,00' });
    api.previewChange.mockReturnValue(of(impact));
    component.review();
    const conflicted = vi.fn();
    component.conflicted.subscribe(conflicted);
    api.applyChange.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: {
      code: 'RECURRENCE_IMPACT_CHANGED', message: 'Os lançamentos mudaram desde a prévia.' } })));
    component.confirm();
    expect(component.error()).toContain('Os dados digitados foram mantidos');
    expect(component.impact()).toBeNull();
    expect(component.changeForm.getRawValue()).toMatchObject({ description: 'Luz nova', amount: '210,00' });
    expect(conflicted).toHaveBeenCalled();
    api.previewChange.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'Nenhum campo foi alterado.' } })));
    component.review();
    expect(component.error()).toBe('Nenhum campo foi alterado.');
    component.editAgain();
    expect(component.error()).toBeNull();
  });

  it('requires a reason to close, offers only earlier periods and sends the reviewed closure', () => {
    expect(component.closureDates()).toEqual(['2026-09-05', '2026-10-05', '2026-11-20']);
    component.openClosure();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Não apaga o histórico');
    component.closureForm.patchValue({ lastDueDate: '2026-10-05', reason: '   ' });
    component.review();
    expect(api.previewClosure).not.toHaveBeenCalled();
    component.closureForm.patchValue({ reason: ' Mudança ' });
    const closure = { ...impact, operation: 'CLOSURE' as const, changedFields: ['lastDueDate'], impactToken: 'token-2',
      occurrences: [{ ...impact.occurrences[0], action: 'REMOVE' as const, reason: 'AFTER_END' }] };
    api.previewClosure.mockReturnValue(of(closure));
    component.review();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Sai da programação');
    api.applyClosure.mockReturnValue(of({ recurrence, replayed: false, change: { type: 'CLOSURE' } }));
    component.confirm();
    expect(api.applyClosure).toHaveBeenCalledWith('rec-1', { version: 2, lastDueDate: '2026-10-05', reason: 'Mudança',
      impactToken: 'token-2' }, 'key-1');
    expect(component.message()).toContain('Nenhuma nova ocorrência');
    component.close();
    expect(component.mode()).toBeNull();
  });

  it('labels effects, fields and values for people', () => {
    expect(component.actionLabel('REVIEW')).toBe('Mantido para revisão');
    expect(component.reasonLabel({ reason: 'OUTSIDE_SCHEDULE' } as never)).toBe('fora da nova programação');
    expect(component.fieldLabel('amount')).toBe('estimativa');
    expect(component.fieldLabel('dueDate')).toBe('vencimento');
    expect(component.valueLabel('categoryId', 'cat-1')).toBe('Casa');
    expect(component.valueLabel('categoryId', null)).toBe('sem categoria');
    expect(component.valueLabel('responsibleUserId', 'user-2')).toBe('Beto');
    expect(component.valueLabel('responsibleUserId', 'gone')).toBe('membro indisponível');
    expect(component.valueLabel('amount', '10.00')).toBe('10.00');
    expect(component.frequencyLabel('ANNUAL')).toBe('Anual');
  });
});
