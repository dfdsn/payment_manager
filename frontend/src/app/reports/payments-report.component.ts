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
import { AccountAccessService } from '../identity/account-access.service';
import { ApiError } from '../identity/initial-setup.service';
import {
  PaymentReport, PaymentRow, PaymentSort, ReportFilters, ReportService, currentMonth, formatCurrency, formatDate,
  formatInstant, formatSigned, monthLabel, paymentFields, shiftMonth,
} from './report.service';

const NONE = '__none__';

/**
 * H06.2: active payments of one month by effective payment date. Another population than the due-date dashboard:
 * a bill due in September and paid in October is in September there and in October here. Totals come from the
 * server over the whole selection; the table is one page of the same selection.
 */
@Component({
  selector: 'app-payments-report',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  templateUrl: './payments-report.component.html',
  styleUrl: './due-dashboard.component.scss',
})
export class PaymentsReportComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly reports = inject(ReportService);
  private readonly expensesApi = inject(ExpenseService);
  private readonly categoryApi = inject(CategoryService);
  private readonly identity = inject(AccountAccessService);
  readonly none = NONE;
  readonly pageSize = 20;
  readonly month = signal<string | null>(null);
  readonly report = signal<PaymentReport | null>(null);
  readonly page = signal(0);
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
  readonly paymentFields = paymentFields;
  private sequence = 0;
  readonly monthControl = this.fb.nonNullable.control('', Validators.pattern(/^\d{4}-\d{2}$/));
  readonly filterForm = this.fb.nonNullable.group({
    search: ['', Validators.maxLength(200)], category: [''], responsible: [''], payerUserId: [''],
    sort: ['PAYMENT_DATE' as PaymentSort], direction: ['ASC' as 'ASC' | 'DESC'],
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
    this.filtersActive.set(!!(value.search.trim() || value.category || value.responsible || value.payerUserId));
    this.page.set(0);
    this.load();
  }

  clearFilters(): void {
    this.filterForm.reset({ search: '', category: '', responsible: '', payerUserId: '', sort: 'PAYMENT_DATE',
      direction: 'ASC' });
    this.filtersActive.set(false);
    this.page.set(0);
    this.load();
  }

  previousPage(): void { if (this.page() > 0) { this.page.update(value => value - 1); this.load(); } }

  nextPage(): void {
    const report = this.report();
    if (report && this.page() + 1 < report.totalPages) { this.page.update(value => value + 1); this.load(); }
  }

  reload(): void { this.load(); }

  filters(): Omit<ReportFilters, 'status'> {
    const value = this.filterForm.getRawValue();
    return {
      search: value.search.trim() || undefined,
      categoryId: value.category && value.category !== NONE ? value.category : undefined,
      withoutCategory: value.category === NONE || undefined,
      responsibleUserId: value.responsible && value.responsible !== NONE ? value.responsible : undefined,
      withoutResponsible: value.responsible === NONE || undefined,
      payerUserId: value.payerUserId || undefined,
    };
  }

  origin(row: PaymentRow): string {
    if (row.origin === 'INSTALLMENT' && row.installment) return `Parcela ${row.installment.number}/${row.installment.count}`;
    return row.origin === 'RECURRENCE' ? 'Recorrência' : 'Avulsa';
  }

  recorded(row: PaymentRow, timeZone: string): string {
    return `${row.batchPayment ? 'Em lote, por' : 'Por'} ${row.recordedByDisplayName} em ${formatInstant(row.recordedAt, timeZone)}`;
  }

  correction(row: PaymentRow, timeZone: string): string | null {
    const last = row.lastCorrection;
    if (!last) return null;
    const times = row.correctionCount > 1 ? ` (${row.correctionCount} correções)` : '';
    return `Corrigida por ${last.actorDisplayName} em ${formatInstant(last.correctedAt, timeZone)}: ${paymentFields(last.changedFields)}${times}`;
  }

  private load(): void {
    const month = this.month();
    if (!month) return;
    const sequence = ++this.sequence;
    const value = this.filterForm.getRawValue();
    this.loading.set(true);
    this.error.set(null);
    this.reports.payments(month, this.filters(), this.page(), this.pageSize, value.sort, value.direction).subscribe({
      next: report => {
        if (sequence !== this.sequence) return;
        this.report.set(report);
        this.loading.set(false);
      },
      error: error => {
        if (sequence !== this.sequence) return;
        // Never keep numbers of another month or filter on screen as if they were current.
        this.report.set(null);
        this.fail(error, 'Não foi possível carregar os pagamentos. Verifique a conexão e tente novamente.');
      },
    });
  }

  private fail(error: unknown, fallback: string): void {
    this.loading.set(false);
    const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
    this.error.set(error instanceof HttpErrorResponse && error.status === 0 ? fallback : body?.message ?? fallback);
  }
}
