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
  ClosingVersion, ClosingVersionList, MonthClosing, MonthClosingList, ReportService, closingChangeDetails, closingChangeLabel, currentMonth,
  formatCurrency, formatDate, formatInstant, monthLabel, shiftMonth,
} from './report.service';

/**
 * E07: symbolic month closing by due date. Before closing, the page shows the current data of the month and asks for
 * a confirmation that names the pending entries; after closing, it shows the saved snapshot as stored by the server.
 * Closing never settles, hides or cancels an expense. H07.2 shows what changed since the saved version; H07.3 saves a
 * new version from the current data and lets every earlier version be read as it was saved.
 */
const RELOAD_CODES = new Set(['MONTH_ALREADY_CLOSED', 'CLOSING_PENDING_CONFIRMATION_REQUIRED', 'CLOSING_VERSION_CONFLICT',
  'MONTH_NOT_CLOSED']);

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
  readonly list = signal<MonthClosingList | null>(null);
  readonly versions = signal<ClosingVersionList | null>(null);
  readonly shownVersion = signal<ClosingVersion | null>(null);
  readonly mode = signal<'close' | 'version'>('close');
  readonly timeZone = signal('America/Sao_Paulo');
  readonly closingChangeDetails = closingChangeDetails;
  readonly closingChangeLabel = closingChangeLabel;
  readonly formatCurrency = formatCurrency;
  readonly formatDate = formatDate;
  readonly formatInstant = formatInstant;
  readonly monthLabel = monthLabel;
  readonly monthControl = this.fb.nonNullable.control('', Validators.pattern(/^\d{4}-\d{2}$/));
  readonly confirmForm = this.fb.nonNullable.group({ acknowledge: false });
  readonly acknowledge = this.confirmForm.controls.acknowledge;
  readonly pendingCount = computed(() => this.closing()?.current.indicators.pendingCount ?? 0);
  private sequence = 0;
  private listYear: number | null = null;
  /** Kept while the same confirmation is retried, so a lost answer never closes the month twice. */
  private key: string | null = null;

  ngOnInit(): void {
    this.identity.context().subscribe({
      next: context => {
        this.timeZone.set(context.timeZone);
        this.goTo(currentMonth(context.timeZone));
      },
      error: error => this.fail(error, 'Não foi possível consultar sua sessão.'),
    });
  }

  goTo(month: string): void {
    this.month.set(month);
    this.versions.set(null);
    this.shownVersion.set(null);
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
    this.mode.set('close');
    this.key = crypto.randomUUID();
    this.acknowledge.setValue(false);
    this.confirmError.set(null);
    this.confirming.set(true);
  }

  /** H07.3: a new version of the closed month, based on the version in force on screen. */
  startVersion(): void {
    this.startConfirmation();
    this.mode.set('version');
  }

  showVersion(version: number): void {
    const month = this.month();
    if (!month) return;
    this.reports.closingVersion(month, version).subscribe({
      next: shown => { if (shown.month === this.month()) this.shownVersion.set(shown); },
      error: error => this.fail(error, 'Não foi possível carregar a versão.'),
    });
  }

  hideVersion(): void { this.shownVersion.set(null); }

  cancelConfirmation(): void {
    this.key = null;
    this.confirming.set(false);
    this.confirmError.set(null);
  }

  confirm(): void {
    const month = this.month();
    const expected = this.closing()?.saved?.version ?? null;
    const generating = this.mode() === 'version';
    if (!month || !this.key || this.submitting() || (generating && expected === null)) return;
    if (this.pendingCount() > 0 && !this.acknowledge.value) {
      this.confirmError.set('Confirme que está ciente das contas pendentes para continuar.');
      return;
    }
    this.submitting.set(true);
    this.confirmError.set(null);
    const request = generating
      ? this.reports.generateClosingVersion(month, expected!, this.acknowledge.value, this.key)
      : this.reports.closeMonth(month, this.acknowledge.value, this.key);
    request.subscribe({
      next: closing => {
        this.submitting.set(false);
        this.key = null;
        this.confirming.set(false);
        if (closing.month === this.month()) this.closing.set(closing);
        this.success.set(generating
          ? `Versão ${closing.saved?.version} de ${monthLabel(closing.month)} gerada. As versões anteriores continuam salvas.`
          : `${monthLabel(closing.month)} fechado. O retrato foi salvo; as contas continuam como estavam.`);
        this.shownVersion.set(null);
        this.loadVersions(closing);
        this.loadList();
      },
      error: error => {
        this.submitting.set(false);
        const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
        if (error instanceof HttpErrorResponse && error.status === 0) {
          // Keep the key: sending again repeats the same request instead of saving twice.
          this.confirmError.set('Sem conexão com o servidor. Nada foi confirmado; tente novamente.');
          return;
        }
        this.confirmError.set(body?.message ?? (generating ? 'Não foi possível gerar a versão.' : 'Não foi possível fechar o mês.'));
        if (body?.code && RELOAD_CODES.has(body.code)) {
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
    this.loadList();
    const sequence = ++this.sequence;
    this.loading.set(true);
    this.error.set(null);
    this.reports.closing(month).subscribe({
      next: closing => {
        if (sequence !== this.sequence) return;
        this.closing.set(closing);
        this.loading.set(false);
        this.loadVersions(closing);
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

  private loadVersions(closing: MonthClosing): void {
    if (!closing.saved) { this.versions.set(null); return; }
    this.reports.closingVersions(closing.month).subscribe({
      next: versions => { if (versions.month === this.month()) this.versions.set(versions); },
      error: () => this.versions.set(null),
    });
  }

  /** The annual list follows the year of the month on screen and is read again with it and after a closing. */
  private loadList(): void {
    const month = this.month();
    if (!month) return;
    const year = Number(month.slice(0, 4));
    this.listYear = year;
    this.reports.closings(year).subscribe({
      next: list => { if (this.listYear === list.year) this.list.set(list); },
      error: () => this.list.set(null),
    });
  }

  private fail(error: unknown, fallback: string): void {
    this.loading.set(false);
    const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
    this.error.set(error instanceof HttpErrorResponse && error.status === 0 ? fallback : body?.message ?? fallback);
  }
}
