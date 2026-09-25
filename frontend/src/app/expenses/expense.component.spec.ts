import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ExpenseComponent } from './expense.component';
import { ExpenseService } from './expense.service';
import { provideRouter } from '@angular/router';

describe('ExpenseComponent', () => {
  let fixture: ComponentFixture<ExpenseComponent>;
  const api = {
    newIdempotencyKey: vi.fn(), list: vi.fn(), create: vi.fn(),
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
      providers: [{ provide: ExpenseService, useValue: api }, provideRouter([])],
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
});
