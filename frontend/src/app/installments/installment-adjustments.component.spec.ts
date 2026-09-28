import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { CategoryService } from '../expenses/category.service';
import { AccountAccessService } from '../identity/account-access.service';
import { InstallmentAdjustmentsComponent } from './installment-adjustments.component';
import { InstallmentItem, InstallmentPurchaseService } from './installment-purchase.service';

const item = (n: number, status: 'PENDING' | 'PAID' | 'CANCELLED'): InstallmentItem => ({ number: n, count: 3,
  amount: n === 3 ? '33.34' : '33.33', dueDate: `2027-0${n}-15`, expenseId: `e-${n}`, status, version: n,
  overdue: false, description: 'Sofá', categoryId: null, categoryName: null, responsibleUserId: null,
  responsibleDisplayName: null, paymentDate: null, paidAmount: null });
const purchase = { id: 'p-1', description: 'Sofá', totalAmount: '100.00', installmentCount: 3, firstDueDate: '2027-01-15',
  lastDueDate: '2027-03-15', categoryId: null, categoryName: null, responsibleUserId: null, responsibleDisplayName: null,
  createdByUserId: 'u', createdByDisplayName: 'Ana', createdAt: '2026-09-28T12:00:00Z', installmentsSum: '100.00',
  installments: [item(1, 'PAID'), item(2, 'PENDING'), item(3, 'PENDING')] };
const changeImpact = { changeType: 'CHANGE', impactToken: 'tok-1', affectedAmount: '66.67', replacement: null,
  affected: [{ number: 2, expenseId: 'e-2', version: 2, amount: '33.33', dueDate: '2027-02-15',
    changes: [{ field: 'categoryId', from: null, to: 'cat-1' }, { field: 'dueDate', from: '2027-02-15', to: '2027-02-20' }] },
  { number: 3, expenseId: 'e-3', version: 3, amount: '33.34', dueDate: '2027-03-15',
    changes: [{ field: 'categoryId', from: null, to: 'cat-1' }, { field: 'dueDate', from: '2027-03-15', to: '2027-03-20' }] }],
  preserved: [{ number: 1, status: 'PAID', reason: 'PAID' }] };
const cancelImpact = { changeType: 'CANCELLATION', impactToken: 'tok-2', affectedAmount: '66.67',
  affected: [{ number: 2, expenseId: 'e-2', version: 2, amount: '33.33', dueDate: '2027-02-15',
    changes: [{ field: 'status', from: 'PENDING', to: 'CANCELLED' }] }],
  preserved: [{ number: 1, status: 'PAID', reason: 'PAID' }, { number: 3, status: 'PENDING', reason: 'NOT_SELECTED' }],
  replacement: { description: 'Sofá (restante)', totalAmount: '70.00', installmentCount: 4, firstDueDate: '2027-02-15',
    lastDueDate: '2027-05-15', regularAmount: '17.50', lastAmount: '17.50', lastInstallmentAdjustment: '0.00',
    installmentsSum: '70.00', installments: [] } };

describe('InstallmentAdjustmentsComponent', () => {
  let fixture: ComponentFixture<InstallmentAdjustmentsComponent>;
  let component: InstallmentAdjustmentsComponent;
  const api = { previewChange: vi.fn(), applyChange: vi.fn(), previewCancellation: vi.fn(), applyCancellation: vi.fn() };
  const text = () => fixture.nativeElement.textContent as string;
  beforeEach(async () => {
    vi.clearAllMocks();
    api.previewChange.mockReturnValue(of(changeImpact));
    api.applyChange.mockReturnValue(of({ changeId: 'c', changeType: 'CHANGE', affectedCount: 2, preservedCount: 1,
      purchase, replacement: null, replayed: false }));
    api.previewCancellation.mockReturnValue(of(cancelImpact));
    api.applyCancellation.mockReturnValue(of({ changeId: 'c2', changeType: 'CANCELLATION', affectedCount: 1,
      preservedCount: 2, purchase, replacement: null, replayed: false }));
    await TestBed.configureTestingModule({ imports: [InstallmentAdjustmentsComponent], providers: [
      { provide: InstallmentPurchaseService, useValue: api },
      { provide: CategoryService, useValue: { list: () => of([{ id: 'cat-1', name: 'Móveis', archived: false },
        { id: 'old', name: 'Antiga', archived: true }]) } },
      { provide: AccountAccessService, useValue: { members: () => of([{ userId: 'u', displayName: 'Ana' }]) } }] })
      .compileComponents();
    fixture = TestBed.createComponent(InstallmentAdjustmentsComponent);
    fixture.componentRef.setInput('purchase', purchase);
    fixture.componentRef.setInput('selected', []);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('reviews the server impact of a change and confirms exactly the reviewed request with its token', () => {
    component.openChange();
    fixture.detectChanges();
    expect(component.changeForm.getRawValue().fromNumber).toBe(2);
    expect(text()).toContain('Valor e quantidade não mudam aqui');
    component.changeForm.patchValue({ changeCategory: true, categoryId: 'cat-1', changeDueDate: true, dueDate: '2027-02-20' });
    component.review();
    fixture.detectChanges();
    expect(api.previewChange).toHaveBeenCalledWith('p-1', { fromNumber: 2, scope: 'THIS_AND_FOLLOWING',
      changedFields: ['categoryId', 'dueDate'], description: null, categoryId: 'cat-1', responsibleUserId: null,
      dueDate: '2027-02-20' });
    expect(text()).toContain('2 parcelas serão alteradas');
    expect(text()).toContain('Categoria: — → Móveis');
    expect(text()).toContain('Vencimento: 2027-03-15 → 2027-03-20');
    expect(text()).toContain('1 (paga, não muda)');
    expect(component.activeCategories().map(c => c.id)).toEqual(['cat-1']);
    const emitted: unknown[] = [];
    component.changed.subscribe(r => emitted.push(r));
    component.confirm();
    expect(api.applyChange).toHaveBeenCalledWith('p-1', expect.objectContaining({ changedFields: ['categoryId', 'dueDate'] }),
      'tok-1', expect.any(String));
    expect(emitted).toHaveLength(1);
    expect(component.mode()).toBeNull();
    expect(component.impact()).toBeNull();
  });

  it('editing after the review discards it and invalid choices are explained before calling the server', () => {
    component.openChange();
    component.review();
    expect(component.error()).toContain('Marque ao menos um campo');
    component.changeForm.patchValue({ changeDescription: true, description: '  ' });
    component.review();
    expect(component.error()).toContain('Descrição');
    component.changeForm.patchValue({ description: 'Novo', changeDueDate: true, dueDate: '' });
    component.review();
    expect(component.error()).toContain('Vencimento');
    component.changeForm.patchValue({ fromNumber: 0 });
    component.review();
    expect(component.error()).toContain('Escolha a parcela');
    expect(api.previewChange).not.toHaveBeenCalled();
    component.changeForm.patchValue({ fromNumber: 3, changeDueDate: false });
    component.review();
    expect(component.impact()).not.toBeNull();
    component.changeForm.patchValue({ description: 'Outro' });
    expect(component.impact()).toBeNull();
  });

  it('a stale review is discarded and must be redone; a network failure keeps it and the key', () => {
    component.openChange();
    component.changeForm.patchValue({ changeDescription: true, description: 'Novo' });
    component.review();
    api.applyChange.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
    component.confirm();
    expect(component.impact()).not.toBeNull();
    const key = api.applyChange.mock.calls[0][3];
    api.applyChange.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
    component.confirm();
    expect(api.applyChange.mock.calls[1][3]).toBe(key);
    component.review();
    api.applyChange.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409,
      error: { code: 'INSTALLMENT_IMPACT_CHANGED', message: 'As parcelas mudaram desde a revisão.', field: 'impactToken' } })));
    component.confirm();
    fixture.detectChanges();
    expect(component.impact()).toBeNull();
    expect(text()).toContain('As parcelas mudaram desde a revisão.');
  });

  it('cancels the selected pending installments with a reason and an optional replacement for the remainder', () => {
    component.openCancel();
    expect(component.mode()).toBeNull();
    fixture.componentRef.setInput('selected', [item(3, 'PENDING'), item(2, 'PENDING')]);
    fixture.detectChanges();
    component.openCancel();
    fixture.detectChanges();
    expect(component.cancelForm.getRawValue()).toMatchObject({ totalAmount: '66.67', installmentCount: 2,
      firstDueDate: '2027-02-15', description: 'Sofá (restante)' });
    expect(text()).toContain('nada é estornado');
    component.review();
    expect(component.error()).toContain('Revise o motivo');
    component.cancelForm.patchValue({ reason: ' Renegociei ', withReplacement: true, totalAmount: '70,00', installmentCount: 4 });
    component.review();
    fixture.detectChanges();
    expect(api.previewCancellation).toHaveBeenCalledWith('p-1', { installmentNumbers: [2, 3], reason: 'Renegociei',
      replacement: { description: 'Sofá (restante)', totalAmount: '70.00', installmentCount: 4, firstDueDate: '2027-02-15',
        categoryId: null, responsibleUserId: null } });
    expect(text()).toContain('somando R$ 66.67');
    expect(text()).toContain('Situação: Pendente → Cancelada');
    expect(text()).toContain('3 (não selecionada)');
    expect(text()).toContain('Nova compra “Sofá (restante)”: R$ 70.00 em 4 parcelas');
    component.confirm();
    expect(api.applyCancellation).toHaveBeenCalledWith('p-1', expect.objectContaining({ installmentNumbers: [2, 3] }),
      'tok-2', expect.any(String));
  });

  it('a new selection discards a cancellation review and errors name the field', () => {
    fixture.componentRef.setInput('selected', [item(2, 'PENDING')]);
    fixture.detectChanges();
    component.openCancel();
    component.cancelForm.patchValue({ reason: 'x' });
    component.review();
    expect(component.impact()).not.toBeNull();
    fixture.componentRef.setInput('selected', [item(2, 'PENDING'), item(3, 'PENDING')]);
    fixture.detectChanges();
    expect(component.impact()).toBeNull();
    api.previewCancellation.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 400,
      error: { message: 'O valor total não permite 2 parcelas.', field: 'totalAmount' } })));
    component.review();
    expect(component.error()).toBe('Valor total: O valor total não permite 2 parcelas.');
    component.close();
    expect(component.mode()).toBeNull();
    expect(component.valueLabel('responsibleUserId', 'u')).toBe('Ana');
    expect(component.valueLabel('responsibleUserId', 'x')).toBe('membro');
    expect(component.valueLabel('categoryId', 'x')).toBe('categoria');
    expect(component.valueLabel('description', '')).toBe('—');
    expect(component.preservedLabel('OUTSIDE_SCOPE')).toContain('só esta');
    expect(component.fieldLabel('outro')).toBe('outro');
  });
});
