import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { CategoryService } from '../expenses/category.service';
import { AccountAccessService } from '../identity/account-access.service';
import { InstallmentPurchaseComponent } from './installment-purchase.component';
import { InstallmentPurchaseService } from './installment-purchase.service';

const preview = { description: 'Sofá', totalAmount: '100.00', installmentCount: 3, firstDueDate: '2027-01-31',
  lastDueDate: '2027-03-31', regularAmount: '33.33', lastAmount: '33.34', lastInstallmentAdjustment: '0.01',
  installmentsSum: '100.00', installments: [
    { number: 1, count: 3, amount: '33.33', dueDate: '2027-01-31', expenseId: null, status: null },
    { number: 2, count: 3, amount: '33.33', dueDate: '2027-02-28', expenseId: null, status: null },
    { number: 3, count: 3, amount: '33.34', dueDate: '2027-03-31', expenseId: null, status: null }] };
const created = { ...preview, id: 'purchase-1', categoryId: 'cat-1', categoryName: 'Casa', responsibleUserId: null,
  responsibleDisplayName: null, createdByUserId: 'actor', createdByDisplayName: 'Ana', createdAt: '2026-09-28T12:00:00Z',
  installments: preview.installments.map(i => ({ ...i, expenseId: `e-${i.number}`, status: 'PENDING' })) };
const expected = { description: 'Sofá', totalAmount: '100.00', installmentCount: 3, firstDueDate: '2027-01-31',
  categoryId: 'cat-1', responsibleUserId: null };

describe('InstallmentPurchaseComponent', () => {
  let fixture: ComponentFixture<InstallmentPurchaseComponent>;
  const api = { newIdempotencyKey: vi.fn(), preview: vi.fn(), create: vi.fn() };
  const text = () => fixture.nativeElement.textContent as string;
  const fill = () => fixture.componentInstance.form.setValue({ description: ' Sofá ', totalAmount: '100,00',
    installmentCount: 3, firstDueDate: '2027-01-31', categoryId: 'cat-1', responsibleUserId: '' });
  beforeEach(async () => {
    vi.clearAllMocks();
    api.newIdempotencyKey.mockReturnValueOnce('key-1').mockReturnValue('key-2');
    api.preview.mockReturnValue(of(preview));
    api.create.mockReturnValue(of(created));
    await TestBed.configureTestingModule({ imports: [InstallmentPurchaseComponent], providers: [provideRouter([]),
      { provide: InstallmentPurchaseService, useValue: api },
      { provide: CategoryService, useValue: { list: () => of([{ id: 'cat-1', name: 'Casa' }]) } },
      { provide: AccountAccessService, useValue: { members: () => of([{ userId: 'actor', displayName: 'Ana' }]) } }] }).compileComponents();
    fixture = TestBed.createComponent(InstallmentPurchaseComponent);
    fixture.detectChanges();
  });

  it('reviews the server calculation, shows the cent adjustment and confirms exactly the reviewed data', () => {
    const component = fixture.componentInstance;
    fill();
    component.review();
    fixture.detectChanges();
    expect(api.preview).toHaveBeenCalledWith(expected);
    expect(text()).toContain('Revise antes de confirmar');
    expect(text()).toContain('2027-02-28');
    expect(text()).toContain('A última parcela tem R$ 0.01 a mais');
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(3);
    expect(api.create).not.toHaveBeenCalled();
    component.confirm();
    fixture.detectChanges();
    expect(api.create).toHaveBeenCalledWith(expected, 'key-1');
    expect(text()).toContain('criada com 3 parcelas pendentes');
    expect(text()).toContain('Ver parcelas em Despesas');
    expect(component.preview()).toBeNull();
    expect(component.form.getRawValue().description).toBe('');
  });

  it('does not call the backend for an invalid form and explains exact divisions', () => {
    const component = fixture.componentInstance;
    component.form.patchValue({ description: 'TV', totalAmount: '10.001', installmentCount: 361, firstDueDate: '' });
    component.review();
    fixture.detectChanges();
    expect(api.preview).not.toHaveBeenCalled();
    expect(text()).toContain('Informe de 2 a 360 parcelas');
    api.preview.mockReturnValue(of({ ...preview, totalAmount: '99.00', regularAmount: '33.00', lastAmount: '33.00',
      lastInstallmentAdjustment: '0.00', installmentsSum: '99.00' }));
    fill();
    component.review();
    fixture.detectChanges();
    expect(text()).toContain('Divisão exata');
  });

  it('invalidates the review when the data changes so nothing unreviewed is created', () => {
    const component = fixture.componentInstance;
    fill();
    component.review();
    component.form.controls.installmentCount.setValue(4);
    fixture.detectChanges();
    expect(component.preview()).toBeNull();
    component.confirm();
    expect(api.create).not.toHaveBeenCalled();
  });

  it('shows backend validation with the field name and requires a new review', () => {
    const component = fixture.componentInstance;
    fill();
    api.create.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400, error: {
      code: 'CATEGORY_NOT_SELECTABLE', field: 'categoryId', message: 'A categoria não está disponível.' } })));
    component.review();
    component.confirm();
    fixture.detectChanges();
    expect(text()).toContain('Categoria: A categoria não está disponível.');
    expect(component.preview()).toBeNull();
    api.preview.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 400, error: {
      field: 'totalAmount', message: 'O valor total não permite 3 parcelas de pelo menos R$ 0,01.' } })));
    component.review();
    fixture.detectChanges();
    expect(text()).toContain('Valor total: O valor total não permite 3 parcelas');
  });

  it('keeps the review and key after a lost response so the retry is idempotent', () => {
    const component = fixture.componentInstance;
    fill();
    component.review();
    api.create.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
    component.confirm();
    fixture.detectChanges();
    expect(text()).toContain('a repetição não duplica parcelas');
    expect(component.preview()).not.toBeNull();
    component.confirm();
    expect(api.create).toHaveBeenNthCalledWith(1, expected, 'key-1');
    expect(api.create).toHaveBeenNthCalledWith(2, expected, 'key-1');
  });
});
