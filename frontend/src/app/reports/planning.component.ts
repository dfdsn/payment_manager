import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { Category, CategoryService } from '../expenses/category.service';
import { ExpenseFilterPerson, ExpenseService } from '../expenses/expense.service';
import { ApiError } from '../identity/initial-setup.service';
import {
  Planning, PlanningFilters, PlanningItem, ReportService, formatCurrency, formatDate, monthLabel,
} from './report.service';

const NONE = '__none__';

/**
 * H06.3: commitments of the current month plus the next 12, by due date. Real expenses (one-off, installments and
 * generated recurrences) and forecasts of recurrences not generated yet are shown apart, and a forecast disappears
 * as soon as its occurrence exists, so nothing is counted twice. Totals come from the server over the whole
 * selection; the list is one page of the chosen month. Opening this page never creates expenses.
 */
@Component({
  selector: 'app-planning',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  templateUrl: './planning.component.html',
  styleUrl: './due-dashboard.component.scss',
})
export class PlanningComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly reports = inject(ReportService);
  private readonly expensesApi = inject(ExpenseService);
  private readonly categoryApi = inject(CategoryService);
  readonly none = NONE;
  readonly pageSize = 20;
  readonly month = signal<string | null>(null);
  readonly planning = signal<Planning | null>(null);
  readonly page = signal(0);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly categories = signal<Category[]>([]);
  readonly responsiblePeople = signal<ExpenseFilterPerson[]>([]);
  readonly filtersActive = signal(false);
  readonly formatCurrency = formatCurrency;
  readonly formatDate = formatDate;
  readonly monthLabel = monthLabel;
  private sequence = 0;
  readonly filterForm = this.fb.nonNullable.group({
    search: ['', Validators.maxLength(200)], category: [''], responsible: [''],
  });

  ngOnInit(): void {
    this.load();
    this.categoryApi.list(true).subscribe({ next: categories => this.categories.set(categories), error: () => undefined });
    this.expensesApi.filterOptions().subscribe({
      next: options => this.responsiblePeople.set(options.responsiblePeople), error: () => undefined,
    });
  }

  selectMonth(month: string): void {
    this.month.set(month);
    this.page.set(0);
    this.load();
  }

  applyFilters(): void {
    if (this.filterForm.invalid) { this.filterForm.markAllAsTouched(); return; }
    const value = this.filterForm.getRawValue();
    this.filtersActive.set(!!(value.search.trim() || value.category || value.responsible));
    this.page.set(0);
    this.load();
  }

  clearFilters(): void {
    this.filterForm.reset({ search: '', category: '', responsible: '' });
    this.filtersActive.set(false);
    this.page.set(0);
    this.load();
  }

  previousPage(): void { if (this.page() > 0) { this.page.update(value => value - 1); this.load(); } }

  nextPage(): void {
    const planning = this.planning();
    if (planning && this.page() + 1 < planning.totalPages) { this.page.update(value => value + 1); this.load(); }
  }

  reload(): void { this.load(); }

  filters(): PlanningFilters {
    const value = this.filterForm.getRawValue();
    return {
      search: value.search.trim() || undefined,
      categoryId: value.category && value.category !== NONE ? value.category : undefined,
      withoutCategory: value.category === NONE || undefined,
      responsibleUserId: value.responsible && value.responsible !== NONE ? value.responsible : undefined,
      withoutResponsible: value.responsible === NONE || undefined,
    };
  }

  origin(item: PlanningItem): string {
    if (item.origin === 'INSTALLMENT' && item.installment) return `Parcela ${item.installment.number}/${item.installment.count}`;
    return item.origin === 'RECURRENCE' ? 'Recorrência' : 'Avulsa';
  }

  situation(item: PlanningItem): string {
    if (item.kind === 'FORECAST') return 'Previsão (ainda não gerada)';
    if (item.status === 'PAID') return `Paga em ${formatDate(item.paymentDate)} por ${formatCurrency(item.paidAmount!)}`;
    return item.overdue ? 'Pendente, atrasada' : 'Pendente';
  }

  private load(): void {
    const sequence = ++this.sequence;
    this.loading.set(true);
    this.error.set(null);
    this.reports.planning(this.month(), this.filters(), this.page(), this.pageSize).subscribe({
      next: planning => {
        if (sequence !== this.sequence) return;
        this.planning.set(planning);
        this.month.set(planning.month);
        this.loading.set(false);
      },
      error: error => {
        if (sequence !== this.sequence) return;
        // Never keep numbers of another filter or month on screen as if they were current.
        this.planning.set(null);
        this.loading.set(false);
        const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
        const fallback = 'Não foi possível carregar o planejamento. Verifique a conexão e tente novamente.';
        this.error.set(error instanceof HttpErrorResponse && error.status === 0 ? fallback : body?.message ?? fallback);
      },
    });
  }
}
