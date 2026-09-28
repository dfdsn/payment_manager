import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';
import { ExpenseComponent } from './expense.component';
import { ExpenseService } from './expense.service';
import { provideRouter } from '@angular/router';
import { AccountAccessService } from '../identity/account-access.service';
import { CategoryService } from './category.service';

describe('ExpenseComponent', () => {
  let fixture: ComponentFixture<ExpenseComponent>;
  const api = {
    newIdempotencyKey: vi.fn(), list: vi.fn(), create: vi.fn(), settle: vi.fn(), get: vi.fn(), correct: vi.fn(),
    reversePayment: vi.fn(), cancel: vi.fn(), settleBatch: vi.fn(), history: vi.fn(), filterOptions: vi.fn(),
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    api.newIdempotencyKey.mockReturnValueOnce('first-key').mockReturnValue('next-key');
    api.list.mockReturnValue(of({
      content: [], page: 0, size: 20, totalElements: 0, totalPages: 0,
      sort: 'REFERENCE_DATE', direction: 'ASC',
    }));
    api.history.mockReturnValue(of({ content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 }));
    api.filterOptions.mockReturnValue(of({ responsiblePeople: [], payerPeople: [] }));
    await TestBed.configureTestingModule({
      imports: [ExpenseComponent],
      providers: [{ provide: ExpenseService, useValue: api }, provideRouter([]),
        { provide: CategoryService, useValue: { list: () => of([]) } },
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

  it('applies combined filters from page zero and keeps the batch selection explicit', () => {
    const component=fixture.componentInstance;
    component.page.set(3);
    component.selectedForBatch.set(new Map([['selected',{id:'selected'} as any]]));
    component.filterForm.patchValue({search:' energia ',dateFrom:'2026-09-01',dateTo:'2026-09-30',status:'OVERDUE',category:'NONE'});
    component.applyFilters();
    expect(component.page()).toBe(0);
    expect(component.selectedForBatch().has('selected')).toBe(true);
    expect(api.list).toHaveBeenLastCalledWith(0,20,'REFERENCE_DATE','ASC',expect.objectContaining({
      search:'energia',dateFrom:'2026-09-01',dateTo:'2026-09-30',status:'OVERDUE',withoutCategory:true,
    }));
  });

  it('does not allow an older search response to replace the latest result', () => {
    const oldResult=new Subject<any>(); const latestResult=new Subject<any>();
    api.list.mockReturnValueOnce(oldResult).mockReturnValueOnce(latestResult);
    const component=fixture.componentInstance;
    component.filterForm.controls.search.setValue('antiga'); component.applyFilters();
    component.filterForm.controls.search.setValue('nova'); component.applyFilters();
    latestResult.next({content:[{id:'new',description:'Nova'}],page:0,size:20,totalElements:1,totalPages:1});
    oldResult.next({content:[{id:'old',description:'Antiga'}],page:0,size:20,totalElements:1,totalPages:1});
    expect(component.expenses().map(item=>item.id)).toEqual(['new']);
  });

  it('creates a pending expense converting decimal comma and refreshes the list', () => {
    api.create.mockReturnValue(of({ id: 'expense' }));
    fixture.componentInstance.form.setValue({
      description: 'Energia', amount: '150,25', status: 'PENDING',
      dueDate: '2026-09-30', paymentDate: '', notes: '', categoryId: '', responsibleUserId: '',
    });

    fixture.componentInstance.submit();
    fixture.detectChanges();

    expect(api.create).toHaveBeenCalledWith({
      description: 'Energia', amount: '150.25', status: 'PENDING',
      dueDate: '2026-09-30', paymentDate: null, notes: null, categoryId: null, responsibleUserId: null,
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
      dueDate: '2026-09-30', paymentDate: '', notes: 'tentar novamente', categoryId: '', responsibleUserId: '',
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
      dueDate: '2026-10-01', notes: null, categoryId: null, responsibleUserId: null,
    }, 'next-key');

    component.openCorrection({ id: 'paid', status: 'PAID', version: 2, description: 'Mercado', amount: '25.50',
      dueDate: null, notes: 'compra', paidAmount: '26.00', paymentDate: '2026-09-25', paidByUserId: 'payer',
      paymentAudit: { notes: 'taxa' } } as any);
    component.editForm.patchValue({ paidAmount: '27,00', paymentDate: '2026-09-26', paymentNotes: 'ajuste' });
    component.confirmCorrection();
    expect(api.correct).toHaveBeenLastCalledWith('paid', {
      version: 2, status: 'PAID', description: 'Mercado', amount: '25.50', dueDate: null, notes: 'compra',
      paidAmount: '27.00', paymentDate: '2026-09-26', paidByUserId: 'payer', paymentNotes: 'ajuste', categoryId: null,
      responsibleUserId: null,
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

  it('requires explicit reason and confirms reversal while preserving its evidence in detail', () => {
    const component = fixture.componentInstance;
    const paid = { id: 'paid', status: 'PAID', version: 2, description: 'Mercado', amount: '25.00',
      dueDate: '2026-09-30', history: [] } as any;
    component.openLifecycleAction(paid, 'REVERSE');
    component.confirmLifecycleAction();
    expect(api.reversePayment).not.toHaveBeenCalled();

    component.actionForm.controls.reason.setValue('Pagamento incorreto');
    api.reversePayment.mockReturnValue(of({}));
    api.get.mockReturnValue(of({ ...paid, status: 'PENDING', version: 3,
      history: [{ type: 'PAYMENT_REVERSED', version: 3, actorDisplayName: 'Autor', occurredAt: '2026-09-25T13:00:00Z', reason: 'Pagamento incorreto' }] }));
    api.history.mockReturnValue(of({ content: [{ type: 'PAYMENT_REVERSED', version: 3, actorDisplayName: 'Autor',
      occurredAt: '2026-09-25T13:00:00Z', reason: 'Pagamento incorreto', changes: [] }],
      page: 0, size: 10, totalElements: 1, totalPages: 1 }));
    component.confirmLifecycleAction();
    expect(api.reversePayment).toHaveBeenCalledWith('paid', 2, 'Pagamento incorreto', 'next-key');
    expect(component.message()).toContain('voltou a ficar pendente');
    expect(component.history()[0].type).toBe('PAYMENT_REVERSED');
  });

  it('preserves cancellation reason after conflict and never retries automatically', () => {
    const component = fixture.componentInstance;
    const pending = { id: 'pending', status: 'PENDING', version: 1, description: 'Energia', amount: '150.00',
      dueDate: '2026-09-30', history: [] } as any;
    component.openLifecycleAction(pending, 'CANCEL');
    component.actionForm.controls.reason.setValue('Duplicada');
    api.cancel.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409 })));
    api.get.mockReturnValue(of({ ...pending, version: 2 }));
    component.confirmLifecycleAction();

    expect(api.cancel).toHaveBeenCalledTimes(1);
    expect(component.actionForm.controls.reason.value).toBe('Duplicada');
    expect(component.errorMessage()).toContain('mudou antes da confirmação');
    expect(component.detail()?.version).toBe(2);
  });

  it('requires correcting a due date before reversing a paid expense without one', () => {
    const component = fixture.componentInstance;
    component.openLifecycleAction({ id: 'paid', status: 'PAID', version: 0, description: 'Compra',
      amount: '80.00', dueDate: null, history: [] } as any, 'REVERSE');
    component.actionForm.controls.reason.setValue('Pagamento incorreto');
    component.confirmLifecycleAction();

    expect(api.reversePayment).not.toHaveBeenCalled();
    expect(component.errorMessage()).toContain('Informe o vencimento pela correção');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('corrija a despesa e informe um vencimento');
  });

  it('selects pending expenses and confirms one atomic batch with common data', () => {
    const component = fixture.componentInstance;
    const first = { id: 'one', status: 'PENDING', version: 2, description: 'Energia', amount: '150.00' } as any;
    const second = { id: 'two', status: 'PENDING', version: 4, description: 'Mercado', amount: '25.50' } as any;
    component.toggleBatch(first);
    component.toggleBatch(second);
    component.openBatch();
    expect(component.batchTotal()).toBe('R$ 175,50');
    component.batchForm.patchValue({ paymentDate: '2026-10-01', paidByUserId: 'payer', confirmed: true });
    api.settleBatch.mockReturnValue(of({ operationId: 'batch-id', replayed: false,
      items: [{ expenseId: 'one' }, { expenseId: 'two' }] }));

    component.confirmBatch();

    expect(api.settleBatch).toHaveBeenCalledWith({
      items: [{ expenseId: 'one', version: 2 }, { expenseId: 'two', version: 4 }],
      paymentDate: '2026-10-01', paidByUserId: 'payer', confirmed: true,
    }, 'next-key');
    expect(component.selectedForBatch().size).toBe(0);
    expect(component.message()).toContain('2 lançamentos quitados');
  });

  it('preserves batch selection data and operation key after an atomic conflict', () => {
    const component = fixture.componentInstance;
    component.toggleBatch({ id: 'one', status: 'PENDING', version: 2,
      description: 'Energia', amount: '150.00' } as any);
    component.openBatch();
    component.batchForm.patchValue({ paymentDate: '2026-10-01', paidByUserId: 'payer', confirmed: true });
    api.settleBatch.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 409 })));

    component.confirmBatch();
    component.confirmBatch();

    expect(api.settleBatch.mock.calls[0]).toEqual(api.settleBatch.mock.calls[1]);
    expect(component.selectedForBatch().has('one')).toBe(true);
    expect(component.batchForm.controls.paymentDate.value).toBe('2026-10-01');
    expect(component.errorMessage()).toContain('lote inteiro foi rejeitado');
  });

  it('assigns an active responsible and loads paginated history in the space timezone', () => {
    const component = fixture.componentInstance;
    component.openCorrection({ id: 'expense', status: 'PENDING', version: 2, description: 'Energia', amount: '150.00',
      dueDate: '2026-09-30', notes: null, responsibleUserId: null } as any);
    component.editForm.controls.responsibleUserId.setValue('payer');
    api.correct.mockReturnValue(of({}));

    component.confirmCorrection();

    expect(api.correct).toHaveBeenCalledWith('expense', expect.objectContaining({ responsibleUserId: 'payer', version: 2 }), 'next-key');

    api.get.mockReturnValue(of({ id: 'expense', status: 'PENDING', version: 3 }));
    api.history.mockReturnValue(of({ content: [{ type: 'EXPENSE_CORRECTED', version: 3,
      actorDisplayName: 'Autor', occurredAt: '2026-09-25T13:00:00Z', reason: null,
      changes: [{ field: 'responsibleUserId', previousValue: null, currentValue: 'Outro' }] }],
      page: 1, size: 10, totalElements: 11, totalPages: 2 }));
    component.loadDetail('expense');
    component.loadHistory('expense', 1);

    expect(api.history).toHaveBeenLastCalledWith('expense', 1, 10);
    expect(component.history()[0].changes[0].currentValue).toBe('Outro');
    expect(component.formatInstant('2026-09-25T13:00:00Z')).toContain('10:00:00');
  });
});
