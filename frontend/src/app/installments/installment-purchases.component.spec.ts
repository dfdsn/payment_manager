import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ExpenseService } from '../expenses/expense.service';
import { AccountAccessService } from '../identity/account-access.service';
import { CategoryService } from '../expenses/category.service';
import { InstallmentPurchasesComponent } from './installment-purchases.component';
import { InstallmentPurchaseService } from './installment-purchase.service';

const progress = { installmentCount: 3, paidCount: 1, pendingCount: 2, overdueCount: 1, cancelledCount: 0,
  paidAmount: '33.33', pendingAmount: '66.67', overdueAmount: '33.33', cancelledAmount: '0.00', nextDueDate: '2026-09-15' };
const summary = { id: 'p-1', description: 'Sofá', totalAmount: '100.00', installmentCount: 3, firstDueDate: '2026-08-15',
  lastDueDate: '2026-10-15', categoryName: null, responsibleDisplayName: null, createdAt: '2026-08-01T12:00:00Z', progress };
const item = (n: number, status: 'PENDING' | 'PAID' | 'CANCELLED', extra = {}) => ({ number: n, count: 3,
  amount: n === 3 ? '33.34' : '33.33', dueDate: `2026-${7 + n}-15`, expenseId: `e-${n}`, status, version: n,
  overdue: false, description: 'Sofá', categoryName: null, responsibleDisplayName: null, paymentDate: null,
  paidAmount: null, ...extra });
const detail = { ...summary, categoryId: null, responsibleUserId: null, createdByUserId: 'u', createdByDisplayName: 'Ana',
  installmentsSum: '100.00', installments: [
    item(1, 'PAID', { paymentDate: '2026-08-14', paidAmount: '33.33' }), item(2, 'PENDING', { overdue: true }),
    item(3, 'PENDING')] };

describe('InstallmentPurchasesComponent', () => {
  let fixture: ComponentFixture<InstallmentPurchasesComponent>;
  const api = { list: vi.fn(), get: vi.fn() };
  const expenses = { newIdempotencyKey: vi.fn(), settleBatch: vi.fn() };
  const text = () => fixture.nativeElement.textContent as string;
  beforeEach(async () => {
    vi.clearAllMocks();
    api.list.mockReturnValue(of({ items: [summary], page: 0, size: 10, totalItems: 1 }));
    api.get.mockReturnValue(of(detail));
    expenses.newIdempotencyKey.mockReturnValueOnce('pay-1').mockReturnValue('pay-2');
    expenses.settleBatch.mockReturnValue(of({ operationId: 'op', replayed: false, items: [{}, {}] }));
    await TestBed.configureTestingModule({ imports: [InstallmentPurchasesComponent], providers: [
      { provide: InstallmentPurchaseService, useValue: api }, { provide: ExpenseService, useValue: expenses },
      { provide: CategoryService, useValue: { list: () => of([]) } },
      { provide: AccountAccessService, useValue: {
        context: () => of({ userId: 'u', timeZone: 'America/Sao_Paulo' }),
        members: () => of([{ userId: 'u', displayName: 'Ana', currentUser: true }]) } }] }).compileComponents();
    fixture = TestBed.createComponent(InstallmentPurchasesComponent);
    fixture.detectChanges();
  });

  it('lists purchases with progress from installment situations and a warning against registering the invoice', () => {
    expect(api.list).toHaveBeenCalledWith(0, 10);
    expect(text()).toContain('1 de 3 pagas · 2 pendentes (1 atrasadas)');
    expect(text()).toContain('Falta pagar R$ 66.67 · próximo vencimento 2026-09-15');
    expect(text()).toContain('não é saldo bancário');
    expect(text()).toContain('Não cadastre a fatura completa');
    expect(fixture.nativeElement.querySelector('progress').getAttribute('max')).toBe('3');
  });

  it('reloads the list when the page reports a new purchase', () => {
    fixture.componentRef.setInput('refresh', 1);
    fixture.detectChanges();
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('shows each installment and lets only pending ones be selected and paid in one atomic batch', () => {
    const component = fixture.componentInstance;
    component.open(summary);
    fixture.detectChanges();
    expect(text()).toContain('Paga em 2026-08-14 (R$ 33.33)');
    expect(text()).toContain('Atrasada');
    expect(fixture.nativeElement.querySelectorAll('tbody input[type=checkbox]').length).toBe(2);
    component.toggle(detail.installments[0] as never);
    expect(component.selection().size).toBe(0);
    component.toggle(detail.installments[1] as never);
    component.toggle(detail.installments[2] as never);
    component.openPayment();
    fixture.detectChanges();
    expect(text()).toContain('Confirmo a quitação integral das 2 parcelas, no total de R$ 66.67');
    component.confirmPayment();
    expect(expenses.settleBatch).not.toHaveBeenCalled();
    component.payForm.controls.confirmed.setValue(true);
    component.confirmPayment();
    fixture.detectChanges();
    expect(expenses.settleBatch).toHaveBeenCalledWith({ items: [{ expenseId: 'e-2', version: 2 },
      { expenseId: 'e-3', version: 3 }], paymentDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), paidByUserId: 'u',
      confirmed: true }, 'pay-1');
    expect(text()).toContain('2 parcelas quitadas de uma vez.');
    expect(component.selection().size).toBe(0);
    expect(api.get).toHaveBeenCalledTimes(2);
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('a rejected batch pays nothing and keeps only still-pending installments with fresh versions', () => {
    const component = fixture.componentInstance;
    component.open(summary);
    component.toggle(detail.installments[1] as never);
    component.toggle(detail.installments[2] as never);
    component.openPayment();
    component.payForm.controls.confirmed.setValue(true);
    expenses.settleBatch.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409 })));
    api.get.mockReturnValueOnce(of({ ...detail, installments: [detail.installments[0],
      item(2, 'PAID', { paymentDate: '2026-09-20', paidAmount: '33.33' }), item(3, 'PENDING', { version: 7 })] }));
    component.confirmPayment();
    fixture.detectChanges();
    expect(text()).toContain('Nenhuma parcela foi quitada');
    expect([...component.selection().values()].map(i => [i.expenseId, i.version])).toEqual([['e-3', 7]]);
    expect(component.paying()).toBe(false);
  });

  it('a network failure keeps the form and the key so a retry cannot pay twice', () => {
    const component = fixture.componentInstance;
    component.open(summary);
    component.toggle(detail.installments[2] as never);
    component.openPayment();
    component.payForm.controls.confirmed.setValue(true);
    expenses.settleBatch.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
    component.confirmPayment();
    expect(component.paying()).toBe(true);
    expect(component.error()).toContain('Nada foi alterado');
    expect(component.installmentsLabel(1)).toBe('da parcela selecionada');
    component.confirmPayment();
    expect(expenses.settleBatch).toHaveBeenLastCalledWith(expect.anything(), 'pay-1');
  });

  it('after a change or cancellation shows the result and reloads the purchase and the list', () => {
    const component = fixture.componentInstance;
    component.open(summary);
    component.toggle(detail.installments[2] as never);
    expect(component.selectedItems().map(i => i.number)).toEqual([3]);
    component.afterAdjustment({ changeId: 'c', changeType: 'CANCELLATION', affectedCount: 1, preservedCount: 2,
      purchase: detail as never, replacement: { ...detail, description: 'Sofá (restante)', installmentCount: 4 } as never,
      replayed: false });
    fixture.detectChanges();
    expect(text()).toContain('1 parcela cancelada; 2 preservadas. Nova compra “Sofá (restante)” criada com 4 parcelas.');
    expect(component.selection().size).toBe(0);
    component.afterAdjustment({ changeId: 'c', changeType: 'CHANGE', affectedCount: 2, preservedCount: 1,
      purchase: detail as never, replacement: null, replayed: false });
    expect(component.message()).toBe('Alteração aplicada a 2 parcelas; 1 preservada.');
    component.afterAdjustment({ changeId: 'c', changeType: 'CHANGE', affectedCount: 1, preservedCount: 1,
      purchase: detail as never, replacement: null, replayed: false });
    expect(component.message()).toBe('Alteração aplicada a 1 parcela; 1 preservada.');
    component.afterAdjustment({ changeId: 'c', changeType: 'CANCELLATION', affectedCount: 2, preservedCount: 1,
      purchase: detail as never, replacement: null, replayed: false });
    expect(component.message()).toBe('2 parcelas canceladas; 1 preservada.');
  });

  it('pages through purchases and reports load failures', () => {
    api.list.mockReturnValueOnce(of({ items: [summary], page: 0, size: 10, totalItems: 11 }));
    fixture.componentInstance.load(0);
    fixture.detectChanges();
    expect(text()).toContain('Página 1');
    fixture.componentInstance.load(1);
    expect(api.list).toHaveBeenLastCalledWith(1, 10);
    api.list.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
    fixture.componentInstance.load(0);
    fixture.detectChanges();
    expect(text()).toContain('Não foi possível carregar as compras parceladas.');
  });
});
