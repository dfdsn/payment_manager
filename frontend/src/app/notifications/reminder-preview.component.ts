import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { ApiError } from '../identity/initial-setup.service';
import { formatDate } from '../reports/report.service';
import { ReminderSummaryCardComponent } from './reminder-summary-card.component';
import { ReminderPreview, ReminderSlot, ReminderSummaryService, SLOT_LABELS } from './reminder-summary.service';

/**
 * H08.2: what a slot would contain with the data as it is now, for both members. The server computes it (calendar,
 * time zone, totals); nothing is saved, materialized or sent.
 */
@Component({
  selector: 'app-reminder-preview',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule,
    ReminderSummaryCardComponent],
  templateUrl: './reminder-preview.component.html',
  styleUrl: './reminder-preview.component.scss',
})
export class ReminderPreviewComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(ReminderSummaryService);
  readonly preview = signal<ReminderPreview | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly slotLabels = SLOT_LABELS;
  readonly date = formatDate;
  readonly form = this.fb.nonNullable.group({ date: '', slot: 'FIRST' as ReminderSlot });

  ngOnInit(): void { this.load(null, 'FIRST'); }

  simulate(): void {
    const { date, slot } = this.form.getRawValue();
    this.load(date || null, slot);
  }

  private load(date: string | null, slot: ReminderSlot): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.preview(date, slot).subscribe({
      next: preview => {
        this.loading.set(false);
        this.preview.set(preview);
        this.form.reset({ date: preview.date, slot: preview.slot });
      },
      error: (error: unknown) => {
        this.loading.set(false);
        const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
        this.error.set(error instanceof HttpErrorResponse && error.status === 401
          ? 'Entre para consultar os lembretes.'
          : body?.message ?? 'Não foi possível calcular a prévia. Verifique a conexão e tente novamente.');
      },
    });
  }
}
