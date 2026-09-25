import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ExpenseComponent } from './expense.component';
import { ExpenseService } from './expense.service';
import { provideRouter } from '@angular/router';
import { AccountAccessService } from '../identity/account-access.service';

describe('ExpenseComponent', () => {
  let fixture: ComponentFixture<ExpenseComponent>;
  const api = {
    newIdempotencyKey: vi.fn(), list: vi.fn(), create: vi.fn(), settle: vi.fn(), get: vi.fn(), correct: vi.fn(),
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    api.newIdempotencyKey.mockReturnValueOnce('first-key').mockReturnValue('next-key');
    api.list.mockReturnValue(of({
      content: [], page: 0, size: 20, totalElements: 0, totalPages: 0,
      sort: 'REFERENCE_DATE', direction: 'ASC',
    }));
    await TestBed.configureTestingModule({
      imports: [ExpenseComponent],
      providers: [{ provide: ExpenseService, useValue: api }, provideRouter([]),
        { provide: AccountAccessService, useValue: {
          context: () => of({ userId: 'actor', timeZone: 'America/Sao_Paulo' }),
          members: () => of([{ userId: 'actor', displayName: 'Autor', currentUser: true }, { userId: 'payer', displayName: 'Outro', currentUser: false }]),
        } }],
    }).compileComponents();
    fixture = TestBed.createComponent(ExpenseComponent);
    fixture.detectChanges();
  });

  it('shows loading completion and a clear empty state', () => {
    expect(fixture.nativeElement.textContent).toContain('Nenhuma despesa cadastrada neste espaço.');
    expect(fixture.nativeElement.textContent).toContain('0 despesas');
  });

  it('creates a pending expense converting decimal comma and refreshes the list', () => {
    api.create.mockReturnValue(of({ id: 'expense' }));
    fixture.componentInstance.form.setValue({
      description: 'Energia', amount: '150,25', status: 'PENDING',
      dueDate: '2026-09-30', paymentDate: '', notes: '',
    });

    fixture.componentInstance.submit();
    fixture.detectChanges();

    expect(api.create).toHaveBeenCalledWith({
      description: 'Energia', amount: '150.25', status: 'PENDING',
      dueDate: '2026-09-30', paymentDate: null, notes: null,
    }, 'first-key');
    expect(api.list).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('Despesa cadastrada com sucesso.');
  });

  it('requires the applicable date when situation changes', () => {
    fixture.componentInstance.form.controls.status.setValue('PAID');
    fixture.componentInstance.form.patchValue({ description: 'Mercado', amount: '20,00' });
    fixture.componentInstance.form.controls.paymentDate.setValue('');

    fixture.componentInstance.submit();

    expect(api.create).not.toHaveBeenCalled();
    expect(fixture.componentInstance.form.controls.paymentDate.invalid).toBe(true);
  });

  it('keeps fields and the same operation key after a failed save', () => {
    api.create.mockReturnValue(throwError(() => new HttpErrorResponse({
      status: 0, error: { message: 'Sem conexão.' },
    })));
    fixture.componentInstance.form.setValue({
      description: 'Internet', amount: '99,90', status: 'PENDING',
      dueDate: '2026-09-30', paymentDate: '', notes: 'tentar novamente',
    });

    fixture.componentInstance.submit();
    fixture.detectChanges();

    expect(fixture.componentInstance.form.controls.description.value).toBe('Internet');
    expect(fixture.nativeElement.textContent).toContain('Sem conexão.');
    expect(api.newIdempotencyKey).toHaveBeenCalledTimes(1);
  });

  it('renders overdue and paid states without relying on color', () => {
    api.list.mockReturnValue(of({
      content: [
        {
          id: '1', description: 'Energia', amount: '150.00', status: 'PENDING', overdue: true,
          referenceDate: '2026-09-24', categoryName: null, responsibleUserId: null,
        },
        {
          id: '2', description: 'Mercado', amount: '25.50', status: 'PAID', overdue: false,
          referenceDate: '2026-09-25', paymentDate: '2026-09-25', categoryName: null,
          responsibleUserId: null, paidByDisplayName: 'Pessoa',
        },
      ],
      page: 0, size: 20, totalElements: 2, totalPages: 1,
      sort: 'REFERENCE_DATE', direction: 'ASC',
    }));

    fixture.componentInstance.ngOnInit();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Atrasada');
    expect(fixture.nativeElement.textContent).toContain('Paga');
    expect(fixture.nativeElement.textContent).toContain('Sem categoria');
  });

  it('suggests the charge and current payer then sends selected payer and preserved version', () => {
    const component = fixture.componentInstance;
    component.openPayment({ id: 'expense', amount: '150.00', version: 3 } as any);
    expect(component.paymentForm.controls.paidAmount.value).toBe('150.00');
    expect(component.paymentForm.controls.paidByUserId.value).toBe('actor');
    component.paymentForm.patchValue({ paidAmount: '145,00', paidByUserId: 'payer', paymentDate: '2026-10-01' });
    api.settle.mockReturnValue(of({}));
    component.confirmPayment();
    expect(api.settle).toHaveBeenCalledWith('expense', {
      version: 3, paidAmount: '145.00', paidByUserId: 'payer', paymentDate: '2026-10-01', paymentNotes: null,
    }, 'next-key');
    expect(component.settling()).toBeNull();
    expect(component.message()).toBe('Quitação registrada com sucesso.');
  });

  it('preserves payment fields key and stale version after conflict or lost response', () => {
    const component = fixture.componentInstance;
    component.openPayment({ id: 'expense', amount: '150.00', version: 3 } as any);
    api.settle.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'Atualize a lista.' } })));
    component.confirmPayment();
    component.confirmPayment();
    expect(api.settle.mock.calls[0]).toEqual(api.settle.mock.calls[1]);
    expect(component.paymentForm.controls.paidAmount.value).toBe('150.00');
    expect(component.settling()?.version).toBe(3);
    expect(component.errorMessage()).toBe('Atualize a lista.');
  });

  it('corrects pending and paid expenses with the loaded version and applicable fields', () => {
    const component = fixture.componentInstance;
    component.openCorrection({ id: 'pending', status: 'PENDING', version: 4, description: 'Energia', amount: '150.00',
      dueDate: '2026-09-30', notes: null } as any);
    component.editForm.patchValue({ description: 'Energia corrigida', amount: '151,25', dueDate: '2026-10-01' });
    api.correct.mockReturnValue(of({}));
    component.confirmCorrection();
    expect(api.correct).toHaveBeenCalledWith('pending', {
      version: 4, status: 'PENDING', description: 'Energia corrigida', amount: '151.25',
      dueDate: '2026-10-01', notes: null,
    }, 'next-key');

    component.openCorrection({ id: 'paid', status: 'PAID', version: 2, description: 'Mercado', amount: '25.50',
      dueDate: null, notes: 'compra', paidAmount: '26.00', paymentDate: '2026-09-25', paidByUserId: 'payer',
      paymentAudit: { notes: 'taxa' } } as any);
    component.editForm.patchValue({ paidAmount: '27,00', paymentDate: '2026-09-26', paymentNotes: 'ajuste' });
    component.confirmCorrection();
    expect(api.correct).toHaveBeenLastCalledWith('paid', {
      version: 2, status: 'PAID', description: 'Mercado', amount: '25.50', dueDate: null, notes: 'compra',
      paidAmount: '27.00', paymentDate: '2026-09-26', paidByUserId: 'payer', paymentNotes: 'ajuste',
    }, 'next-key');
  });

  it('preserves typed fields on conflict, loads current data and requires an explicit manual retry', () => {
    const component = fixture.componentInstance;
    component.openCorrection({ id: 'expense', status: 'PENDING', version: 3, description: 'Energia', amount: '150.00',
      dueDate: '2026-09-30', notes: null } as any);
    component.editForm.controls.description.setValue('Minha correção');
    api.correct.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409 })));
    api.get.mockReturnValue(of({ id: 'expense', status: 'PENDING', version: 4, description: 'Alteração concorrente',
      amount: '152.00', dueDate: '2026-10-01', referenceDate: '2026-10-01' }));

    component.confirmCorrection();
    fixture.detectChanges();

    expect(api.get).toHaveBeenCalledWith('expense');
    expect(component.conflictCurrent()?.version).toBe(4);
    expect(component.editForm.controls.description.value).toBe('Minha correção');
    expect(api.correct).toHaveBeenCalledTimes(1);
    expect(fixture.nativeElement.textContent).toContain('nada será reenviado automaticamente');

    component.useCurrentVersion();
    expect(component.editing()?.version).toBe(4);
    expect(component.editForm.controls.description.value).toBe('Minha correção');
    expect(api.correct).toHaveBeenCalledTimes(1);
  });
});
