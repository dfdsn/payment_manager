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
    this.form.controls.status.valueChanges.subscribe(status => this.updateDateRules(status));
    this.load();
  }

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
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
