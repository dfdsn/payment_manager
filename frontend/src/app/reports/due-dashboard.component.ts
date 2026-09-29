import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { Category, CategoryService } from '../expenses/category.service';
import { Expense, ExpenseFilterPerson, ExpenseService, ExpenseStatusFilter } from '../expenses/expense.service';
import { AccountAccessService } from '../identity/account-access.service';
import { ApiError } from '../identity/initial-setup.service';
import {
  DueDashboard, ReportFilters, ReportService, currentMonth, formatCurrency, formatDate, formatSigned, monthBounds,
  monthLabel, shiftMonth,
} from './report.service';

const NONE = '__none__';

/**
 * H06.1: one month by due date. Indicators come from the server over every entry of the selection; the list below
 * uses the same filters and period, so what is listed is exactly what is summed (cancelled entries only listed).
 */
@Component({
  selector: 'app-due-dashboard',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  templateUrl: './due-dashboard.component.html',
  styleUrl: './due-dashboard.component.scss',
})
export class DueDashboardComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly reports = inject(ReportService);
  private readonly expensesApi = inject(ExpenseService);
  private readonly categoryApi = inject(CategoryService);
  private readonly identity = inject(AccountAccessService);
  readonly none = NONE;
  readonly pageSize = 20;
  readonly month = signal<string | null>(null);
  readonly dashboard = signal<DueDashboard | null>(null);
  readonly expenses = signal<Expense[]>([]);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly totalElements = signal(0);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly categories = signal<Category[]>([]);
  readonly responsiblePeople = signal<ExpenseFilterPerson[]>([]);
  readonly payerPeople = signal<ExpenseFilterPerson[]>([]);
  readonly filtersActive = signal(false);
  readonly formatCurrency = formatCurrency;
  readonly formatSigned = formatSigned;
  readonly formatDate = formatDate;
  readonly monthLabel = monthLabel;
  private sequence = 0;
  readonly monthControl = this.fb.nonNullable.control('', Validators.pattern(/^\d{4}-\d{2}$/));
  readonly filterForm = this.fb.nonNullable.group({
    search: ['', Validators.maxLength(200)], category: [''], responsible: [''], payerUserId: [''],
    status: ['ACTIVE' as ExpenseStatusFilter],
  });

  ngOnInit(): void {
    this.identity.context().subscribe({
      next: context => this.goTo(currentMonth(context.timeZone)),
      error: error => this.fail(error, 'Não foi possível consultar sua sessão.'),
    });
    this.categoryApi.list(true).subscribe({ next: categories => this.categories.set(categories), error: () => undefined });
    this.expensesApi.filterOptions().subscribe({
      next: options => { this.responsiblePeople.set(options.responsiblePeople); this.payerPeople.set(options.payerPeople); },
      error: () => undefined,
    });
  }

  goTo(month: string): void {
    this.month.set(month);
    this.monthControl.setValue(month, { emitEvent: false });
    this.page.set(0);
    this.load();
  }

  previousMonth(): void { const month = this.month(); if (month) this.goTo(shiftMonth(month, -1)); }

  nextMonth(): void { const month = this.month(); if (month) this.goTo(shiftMonth(month, 1)); }

  chooseMonth(): void {
    const value = this.monthControl.value;
    if (this.monthControl.invalid || !value) { this.monthControl.markAsTouched(); return; }
    this.goTo(value);
  }

  applyFilters(): void {
    if (this.filterForm.invalid) { this.filterForm.markAllAsTouched(); return; }
    const value = this.filterForm.getRawValue();
    this.filtersActive.set(!!(value.search.trim() || value.category || value.responsible || value.payerUserId
      || value.status !== 'ACTIVE'));
    this.page.set(0);
    this.load();
  }

  clearFilters(): void {
    this.filterForm.reset({ search: '', category: '', responsible: '', payerUserId: '', status: 'ACTIVE' });
    this.filtersActive.set(false);
    this.page.set(0);
    this.load();
  }

  previousPage(): void { if (this.page() > 0) { this.page.update(value => value - 1); this.load(); } }

  nextPage(): void { if (this.page() + 1 < this.totalPages()) { this.page.update(value => value + 1); this.load(); } }

  reload(): void { this.load(); }

  filters(): ReportFilters {
    const value = this.filterForm.getRawValue();
    return {
      search: value.search.trim() || undefined,
      categoryId: value.category && value.category !== NONE ? value.category : undefined,
      withoutCategory: value.category === NONE || undefined,
      responsibleUserId: value.responsible && value.responsible !== NONE ? value.responsible : undefined,
      withoutResponsible: value.responsible === NONE || undefined,
      payerUserId: value.payerUserId || undefined,
      status: value.status,
    };
  }

  situation(expense: Expense): string {
    if (expense.status === 'CANCELLED') return 'Cancelada (fora dos totais)';
    if (expense.status === 'PAID') return 'Paga';
    return expense.overdue ? 'Atrasada' : 'Pendente';
  }

  origin(expense: Expense): string {
    if (expense.origin === 'INSTALLMENT' && expense.installment)
      return `Parcela ${expense.installment.number}/${expense.installment.count}`;
    return expense.origin === 'RECURRENCE' ? 'Recorrência' : 'Avulsa';
  }

  private load(): void {
    const month = this.month();
    if (!month) return;
    const sequence = ++this.sequence;
    const filters = this.filters();
    const { from, to } = monthBounds(month);
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      dashboard: this.reports.dueDashboard(month, filters),
      list: this.expensesApi.list(this.page(), this.pageSize, 'REFERENCE_DATE', 'ASC',
        { ...filters, dateFrom: from, dateTo: to, dateBasis: 'DUE_DATE' }),
    }).subscribe({
      next: ({ dashboard, list }) => {
        if (sequence !== this.sequence) return;
        this.dashboard.set(dashboard);
        this.expenses.set(list.content);
        this.totalPages.set(list.totalPages);
        this.totalElements.set(list.totalElements);
        this.loading.set(false);
      },
      error: error => {
        if (sequence !== this.sequence) return;
        // Never keep numbers of another month or filter on screen as if they were current.
        this.dashboard.set(null);
        this.expenses.set([]);
        this.fail(error, 'Não foi possível carregar o painel. Verifique a conexão e tente novamente.');
      },
    });
  }

  private fail(error: unknown, fallback: string): void {
    this.loading.set(false);
    const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
    this.error.set(error instanceof HttpErrorResponse && error.status === 0 ? fallback : body?.message ?? fallback);
  }
}
