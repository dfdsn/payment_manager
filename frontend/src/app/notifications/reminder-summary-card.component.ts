import { Component, computed, input } from '@angular/core';
import { formatCurrency, formatDate } from '../reports/report.service';
import { CHANNEL_REASON_LABELS, ReminderSummary } from './reminder-summary.service';

/** The content of one summary: what the message says (up to five bills) and the full list behind the link. */
@Component({
  selector: 'app-reminder-summary-card',
  templateUrl: './reminder-summary-card.component.html',
  styleUrl: './reminder-summary-card.component.scss',
})
export class ReminderSummaryCardComponent {
  readonly summary = input.required<ReminderSummary>();
  readonly details = computed(() => this.summary().items.slice(0, this.summary().detailCount));
  readonly money = formatCurrency;
  readonly date = formatDate;

  channelText(channel: ReminderSummary['channels'][number]): string {
    const name = channel.channel === 'IN_APP' ? 'No aplicativo (os dois membros)' : 'WhatsApp do administrador';
    if (channel.status === 'PLANNED') return `${name}: previsto`;
    return `${name}: não será enviado (${CHANNEL_REASON_LABELS[channel.reason ?? ''] ?? channel.reason})`;
  }

  plural(count: number, one: string, many: string): string {
    return `${count} ${count === 1 ? one : many}`;
  }
}
