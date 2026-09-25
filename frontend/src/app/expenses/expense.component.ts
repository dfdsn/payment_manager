import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ApiError } from '../identity/initial-setup.service';
import { AccountAccessService, SpaceMember } from '../identity/account-access.service';
import {
  Expense, ExpenseService, ExpenseSort, ExpenseStatus, SortDirection,
} from './expense.service';

@Component({
  selector: 'app-expense',
  imports: [
    ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule,
    MatFormFieldModule, MatInputModule,
  ],
  templateUrl: './expense.component.html',
  styleUrl: './expense.component.scss',
})
export class ExpenseComponent implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly expensesApi = inject(ExpenseService);
  private readonly identity = inject(AccountAccessService);
  readonly members = signal<SpaceMember[]>([]);
  readonly settling = signal<Expense | null>(null);
  readonly editing = signal<Expense | null>(null);
  readonly conflictCurrent = signal<Expense | null>(null);
  private paymentKey = '';
  private correctionKey = '';
  private today = '';
  readonly paymentForm = this.formBuilder.nonNullable.group({
    paidAmount: ['', [Validators.required, Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)]],
    paymentDate: ['', Validators.required],
    paidByUserId: ['', Validators.required],
    paymentNotes: ['', Validators.maxLength(2000)],
  });
  readonly editForm = this.formBuilder.nonNullable.group({
    description: ['', [Validators.required, Validators.maxLength(200)]],
    amount: ['', [Validators.required, Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)]],
    dueDate: [''],
    notes: ['', Validators.maxLength(2000)],
    paidAmount: ['', Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)],
    paymentDate: [''],
    paidByUserId: [''],
    paymentNotes: ['', Validators.maxLength(2000)],
  });

  readonly loading = signal(true);
  readonly submitting = signal(false);
  readonly message = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly expenses = signal<Expense[]>([]);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly totalElements = signal(0);
  readonly sort = signal<ExpenseSort>('REFERENCE_DATE');
  readonly direction = signal<SortDirection>('ASC');
  private idempotencyKey = this.expensesApi.newIdempotencyKey();

  readonly form = this.formBuilder.nonNullable.group({
    description: ['', [Validators.required, Validators.maxLength(200)]],
    amount: ['', [Validators.required, Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)]],
    status: ['PENDING' as ExpenseStatus, Validators.required],
    dueDate: ['', Validators.required],
    paymentDate: [''],
    notes: ['', Validators.maxLength(2000)],
  });

  ngOnInit(): void {
    this.identity.context().subscribe({ next: context => {
      const parts = new Intl.DateTimeFormat('en-CA', { timeZone: context.timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date());
      const part = (type: string) => parts.find(value => value.type === type)!.value;
      this.today = `${part('year')}-${part('month')}-${part('day')}`;
      this.paymentForm.patchValue({ paymentDate: this.today, paidByUserId: context.userId });
    }, error: error => this.handleError(error, 'Não foi possível consultar sua sessão.') });
    this.identity.members().subscribe({ next: members => this.members.set(members),
      error: error => this.handleError(error, 'Não foi possível carregar os pagadores.') });
    this.form.controls.status.valueChanges.subscribe(status => this.updateDateRules(status));
    this.load();
  }

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    if (value.status === 'PAID') {
      this.paymentForm.controls.paymentDate.setValue(value.paymentDate);
      if (this.paymentForm.invalid) { this.paymentForm.markAllAsTouched(); return; }
    }
    this.submitting.set(true);
    this.message.set(null);
    this.errorMessage.set(null);
    this.expensesApi.create({
      description: value.description,
      amount: value.amount.replace(',', '.'),
      status: value.status,
      dueDate: value.dueDate || null,
      paymentDate: value.paymentDate || null,
      notes: value.notes.trim() || null,
      ...(value.status === 'PAID' ? {
        paidAmount: this.paymentForm.controls.paidAmount.value.replace(',', '.'),
        paidByUserId: this.paymentForm.controls.paidByUserId.value,
        paymentNotes: this.paymentForm.controls.paymentNotes.value.trim() || null,
      } : {}),
    }, this.idempotencyKey).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => {
        this.message.set('Despesa cadastrada com sucesso.');
        this.idempotencyKey = this.expensesApi.newIdempotencyKey();
        this.form.reset({
          description: '', amount: '', status: 'PENDING', dueDate: '', paymentDate: '', notes: '',
        });
        this.page.set(0);
        this.load();
      },
      error: error => this.handleError(error, 'Não foi possível salvar. Os campos foram preservados para nova tentativa.'),
    });
  }

  changeSort(sort: ExpenseSort): void {
    this.sort.set(sort);
    this.page.set(0);
    this.load();
  }

  openPayment(expense: Expense): void {
    this.settling.set(expense);
    this.paymentKey = this.expensesApi.newIdempotencyKey();
    this.paymentForm.reset({ paidAmount: expense.amount, paymentDate: this.today,
      paidByUserId: this.members().find(member => member.currentUser)?.userId ?? '', paymentNotes: '' });
    this.errorMessage.set(null);
    this.message.set(null);
  }

  confirmPayment(): void {
    const expense = this.settling();
    if (!expense || this.submitting()) return;
    if (this.paymentForm.invalid) { this.paymentForm.markAllAsTouched(); return; }
    const values = this.paymentForm.getRawValue();
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.expensesApi.settle(expense.id, { ...values, version: expense.version,
      paidAmount: values.paidAmount.replace(',', '.'), paymentNotes: values.paymentNotes.trim() || null,
    }, this.paymentKey).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => { this.settling.set(null); this.message.set('Quitação registrada com sucesso.'); this.load(); },
      error: error => this.handleError(error, 'Não foi possível quitar. Os campos foram preservados para nova tentativa.'),
    });
  }

  openCorrection(expense: Expense): void {
    this.editing.set(expense);
    this.conflictCurrent.set(null);
    this.correctionKey = this.expensesApi.newIdempotencyKey();
    this.editForm.reset({
      description: expense.description, amount: expense.amount, dueDate: expense.dueDate ?? '',
      notes: expense.notes ?? '', paidAmount: expense.paidAmount ?? '', paymentDate: expense.paymentDate ?? '',
      paidByUserId: expense.paidByUserId ?? '', paymentNotes: expense.paymentAudit?.notes ?? '',
    });
    if (expense.status === 'PAID') {
      this.editForm.controls.paidAmount.setValidators([Validators.required, Validators.pattern(/^\d{1,8}([.,]\d{1,2})?$/)]);
      this.editForm.controls.paymentDate.setValidators(Validators.required);
      this.editForm.controls.paidByUserId.setValidators(Validators.required);
      this.editForm.controls.dueDate.clearValidators();
    } else {
      this.editForm.controls.dueDate.setValidators(Validators.required);
      this.editForm.controls.paidAmount.clearValidators();
      this.editForm.controls.paymentDate.clearValidators();
      this.editForm.controls.paidByUserId.clearValidators();
    }
    Object.values(this.editForm.controls).forEach(control => control.updateValueAndValidity());
    this.errorMessage.set(null);
    this.message.set(null);
  }

  confirmCorrection(): void {
    const expense = this.editing();
    if (!expense || this.submitting()) return;
    if (this.editForm.invalid) { this.editForm.markAllAsTouched(); return; }
    const value = this.editForm.getRawValue();
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.conflictCurrent.set(null);
    this.expensesApi.correct(expense.id, {
      version: expense.version, status: expense.status, description: value.description,
      amount: value.amount.replace(',', '.'), dueDate: value.dueDate || null, notes: value.notes.trim() || null,
      ...(expense.status === 'PAID' ? {
        paidAmount: value.paidAmount.replace(',', '.'), paymentDate: value.paymentDate,
        paidByUserId: value.paidByUserId, paymentNotes: value.paymentNotes.trim() || null,
      } : {}),
    }, this.correctionKey).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => { this.editing.set(null); this.message.set('Despesa corrigida com sucesso.'); this.load(); },
      error: error => {
        if (error instanceof HttpErrorResponse && error.status === 409) {
          this.errorMessage.set('Outra alteração foi salva antes da sua. Seus campos foram preservados; consulte os dados atuais antes de reaplicar.');
          this.expensesApi.get(expense.id).subscribe({
            next: current => this.conflictCurrent.set(current),
            error: refreshError => this.handleError(refreshError, 'Houve conflito e não foi possível consultar os dados atuais.'),
          });
        } else this.handleError(error, 'Não foi possível corrigir. Os campos foram preservados para nova tentativa.');
      },
    });
  }

  useCurrentVersion(): void {
    const current = this.conflictCurrent();
    if (!current) return;
    this.editing.set(current);
    this.conflictCurrent.set(null);
    this.correctionKey = this.expensesApi.newIdempotencyKey();
    this.errorMessage.set(null);
  }

  toggleDirection(): void {
    this.direction.update(value => value === 'ASC' ? 'DESC' : 'ASC');
    this.page.set(0);
    this.load();
  }

  previous(): void {
    if (this.page() === 0) return;
    this.page.update(value => value - 1);
    this.load();
  }

  next(): void {
    if (this.page() + 1 >= this.totalPages()) return;
    this.page.update(value => value + 1);
    this.load();
  }

  formatCurrency(value: string): string {
    return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(Number(value));
  }

  formatDate(value: string | null): string {
    if (!value) return 'Não informado';
    const [year, month, day] = value.split('-');
    return `${day}/${month}/${year}`;
  }

  private updateDateRules(status: ExpenseStatus): void {
    const dueDate = this.form.controls.dueDate;
    const paymentDate = this.form.controls.paymentDate;
    if (status === 'PENDING') {
      dueDate.setValidators(Validators.required);
      paymentDate.clearValidators();
      paymentDate.setValue('');
    } else {
      dueDate.clearValidators();
      paymentDate.setValidators(Validators.required);
      paymentDate.setValue(this.today);
      this.paymentForm.patchValue({ paidAmount: this.form.controls.amount.value,
        paidByUserId: this.members().find(member => member.currentUser)?.userId ?? '', paymentNotes: '' });
    }
    dueDate.updateValueAndValidity();
    paymentDate.updateValueAndValidity();
  }

  private load(): void {
    this.loading.set(true);
    this.errorMessage.set(null);
    this.expensesApi.list(this.page(), 20, this.sort(), this.direction())
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: result => {
          this.expenses.set(result.content);
          this.page.set(result.page);
          this.totalPages.set(result.totalPages);
          this.totalElements.set(result.totalElements);
        },
        error: error => this.handleError(error, 'Não foi possível carregar as despesas.'),
      });
  }

  private handleError(error: HttpErrorResponse, fallback: string): void {
    const apiError = error.error as ApiError | undefined;
    this.errorMessage.set(apiError?.message ?? fallback);
  }
}
