import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { RouterLink } from '@angular/router';
import { AccountAccessService } from '../identity/account-access.service';
import { ApiError } from '../identity/initial-setup.service';
import { ClosingSnapshotComponent } from './closing-snapshot.component';
import {
  MonthClosing, ReportService, currentMonth, formatCurrency, formatDate, formatInstant, monthLabel, shiftMonth,
} from './report.service';

/**
 * E07: symbolic month closing by due date. Before closing, the page shows the current data of the month and asks for
 * a confirmation that names the pending entries; after closing, it shows the saved snapshot as stored by the server.
 * Closing never settles, hides or cancels an expense.
 */
@Component({
  selector: 'app-month-closing',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule, ClosingSnapshotComponent],
  templateUrl: './month-closing.component.html',
  styleUrl: './due-dashboard.component.scss',
})
export class MonthClosingComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly reports = inject(ReportService);
  private readonly identity = inject(AccountAccessService);
  readonly month = signal<string | null>(null);
  readonly closing = signal<MonthClosing | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly confirming = signal(false);
  readonly submitting = signal(false);
  readonly confirmError = signal<string | null>(null);
  readonly success = signal<string | null>(null);
  readonly formatCurrency = formatCurrency;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly monthLabel = monthLabel;
  readonly monthControl = this.fb.nonNullable.control('', Validators.pattern(/^\d{4}-\d{2}$/));
  readonly confirmForm = this.fb.nonNullable.group({ acknowledge: false });
  readonly acknowledge = this.confirmForm.controls.acknowledge;
  readonly pendingCount = computed(() => this.closing()?.current.indicators.pendingCount ?? 0);
  private sequence = 0;
  /** Kept while the same confirmation is retried, so a lost answer never closes the month twice. */
  private key: string | null = null;

  ngOnInit(): void {
    this.identity.context().subscribe({
      next: context => this.goTo(currentMonth(context.timeZone)),
      error: error => this.fail(error, 'Não foi possível consultar sua sessão.'),
    });
  }

  goTo(month: string): void {
    this.month.set(month);
    this.monthControl.setValue(month, { emitEvent: false });
    this.cancelConfirmation();
    this.success.set(null);
    this.load();
  }

  previousMonth(): void { const month = this.month(); if (month) this.goTo(shiftMonth(month, -1)); }

  nextMonth(): void { const month = this.month(); if (month) this.goTo(shiftMonth(month, 1)); }

  chooseMonth(): void {
    const value = this.monthControl.value;
    if (this.monthControl.invalid || !value) { this.monthControl.markAsTouched(); return; }
    this.goTo(value);
  }

  reload(): void { this.load(); }

  startConfirmation(): void {
    this.key = crypto.randomUUID();
    this.acknowledge.setValue(false);
    this.confirmError.set(null);
    this.confirming.set(true);
  }

  cancelConfirmation(): void {
    this.key = null;
    this.confirming.set(false);
    this.confirmError.set(null);
  }

  confirm(): void {
    const month = this.month();
    if (!month || !this.key || this.submitting()) return;
    if (this.pendingCount() > 0 && !this.acknowledge.value) {
      this.confirmError.set('Confirme que está ciente das contas pendentes para fechar o mês.');
      return;
    }
    this.submitting.set(true);
    this.confirmError.set(null);
    this.reports.closeMonth(month, this.acknowledge.value, this.key).subscribe({
      next: closing => {
        this.submitting.set(false);
        this.key = null;
        this.confirming.set(false);
        if (closing.month === this.month()) this.closing.set(closing);
        this.success.set(`${monthLabel(closing.month)} fechado. O retrato foi salvo; as contas continuam como estavam.`);
      },
      error: error => {
        this.submitting.set(false);
        const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
        if (error instanceof HttpErrorResponse && error.status === 0) {
          // Keep the key: sending again repeats the same request instead of creating another closing.
          this.confirmError.set('Sem conexão com o servidor. Nada foi confirmado; tente novamente.');
          return;
        }
        this.confirmError.set(body?.message ?? 'Não foi possível fechar o mês.');
        if (body?.code === 'MONTH_ALREADY_CLOSED' || body?.code === 'CLOSING_PENDING_CONFIRMATION_REQUIRED') {
          // The month changed since it was loaded: show the server's current state before asking again.
          this.key = null;
          this.confirming.set(false);
          this.load(body.message);
        }
      },
    });
  }

  private load(notice: string | null = null): void {
    const month = this.month();
    if (!month) return;
    const sequence = ++this.sequence;
    this.loading.set(true);
    this.error.set(null);
    this.reports.closing(month).subscribe({
      next: closing => {
        if (sequence !== this.sequence) return;
        this.closing.set(closing);
        this.loading.set(false);
        if (notice) this.error.set(notice);
      },
      error: error => {
        if (sequence !== this.sequence) return;
        // Never keep another month's numbers on screen as if they were this month's.
        this.closing.set(null);
        this.fail(error, 'Não foi possível carregar o fechamento. Verifique a conexão e tente novamente.');
      },
    });
  }

  private fail(error: unknown, fallback: string): void {
    this.loading.set(false);
    const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
    this.error.set(error instanceof HttpErrorResponse && error.status === 0 ? fallback : body?.message ?? fallback);
  }
}
