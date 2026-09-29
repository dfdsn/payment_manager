import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiError } from '../identity/initial-setup.service';
import { formatDate, formatInstant } from '../reports/report.service';
import { ReminderSummaryCardComponent } from './reminder-summary-card.component';
import { ReminderSummary, ReminderSummaryService, SLOT_LABELS } from './reminder-summary.service';

/** H08.2: target of the summary link. Requires a session; shows the full list of the summary as generated. */
@Component({
  selector: 'app-reminder-summary',
  imports: [RouterLink, MatCardModule, ReminderSummaryCardComponent],
  templateUrl: './reminder-summary.component.html',
  styleUrl: './reminder-preview.component.scss',
})
export class ReminderSummaryComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ReminderSummaryService);
  readonly summary = signal<ReminderSummary | null>(null);
  readonly error = signal<string | null>(null);
  readonly unauthenticated = signal(false);
  readonly slotLabels = SLOT_LABELS;
  readonly date = formatDate;

  ngOnInit(): void {
    this.api.summary(this.route.snapshot.paramMap.get('id') ?? '').subscribe({
      next: summary => this.summary.set(summary),
      error: (error: unknown) => {
        const status = error instanceof HttpErrorResponse ? error.status : 0;
        const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
        this.unauthenticated.set(status === 401);
        this.error.set(status === 401 ? 'Entre para ver este resumo.'
          : status === 404 || status === 400 ? 'Resumo não encontrado no seu espaço.'
            : body?.message ?? 'Não foi possível carregar o resumo. Verifique a conexão e tente novamente.');
      },
    });
  }

  generatedAt(s: ReminderSummary): string { return s.generatedAt ? formatInstant(s.generatedAt, s.timeZone) : ''; }
}
