import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiError } from '../identity/initial-setup.service';
import { formatDate, formatInstant } from '../reports/report.service';
import { ReminderSummaryCardComponent } from './reminder-summary-card.component';
import { ReminderSummary, ReminderSummaryService, SLOT_LABELS, WhatsAppDelivery } from './reminder-summary.service';

/**
 * H08.2: target of the summary link. Requires a session; shows the full list of the summary as generated.
 * H08.4: the administrator also sees what happened on WhatsApp; the guest gets 403 there and sees nothing of it.
 */
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
  readonly whatsapp = signal<WhatsAppDelivery | null>(null);
  readonly whatsappError = signal<string | null>(null);
  readonly slotLabels = SLOT_LABELS;
  readonly date = formatDate;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id') ?? '';
    this.api.summary(id).subscribe({
      next: summary => { this.summary.set(summary); this.loadWhatsApp(id); },
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

  /** Instants of the WhatsApp delivery that exist, in order; acceptance and delivery stay separate lines. */
  whatsappSteps(w: WhatsAppDelivery): { label: string; at: string }[] {
    const zone = this.summary()?.timeZone ?? 'America/Sao_Paulo';
    const steps: [string, string | null][] = [['Tentativa', w.attemptedAt], ['Aceito pela Meta', w.acceptedAt],
      ['Enviado', w.sentAt], ['Entregue', w.deliveredAt], ['Lido', w.readAt], ['Falha', w.failedAt]];
    return steps.filter(([, at]) => !!at).map(([label, at]) => ({ label, at: formatInstant(at!, zone) }));
  }

  private loadWhatsApp(id: string): void {
    this.api.whatsapp(id).subscribe({
      next: delivery => this.whatsapp.set(delivery),
      error: (error: unknown) => {
        const status = error instanceof HttpErrorResponse ? error.status : 0;
        // 403: the reader is not the administrator; WhatsApp details are not theirs to see.
        this.whatsappError.set(status === 403 ? null : 'Não foi possível consultar o envio pelo WhatsApp.');
      },
    });
  }

  generatedAt(s: ReminderSummary): string { return s.generatedAt ? formatInstant(s.generatedAt, s.timeZone) : ''; }
}
