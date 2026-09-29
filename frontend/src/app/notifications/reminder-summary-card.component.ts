import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { formatCurrency, formatDate } from '../reports/report.service';
import { CHANNEL_REASON_LABELS, ReminderSummary, ReminderSummaryItem } from './reminder-summary.service';

/** The content of one summary: what the message says (up to five bills) and the full list behind the link. */
@Component({
  selector: 'app-reminder-summary-card',
  imports: [RouterLink],
  templateUrl: './reminder-summary-card.component.html',
  styleUrl: './reminder-summary-card.component.scss',
})
export class ReminderSummaryCardComponent {
  readonly summary = input.required<ReminderSummary>();
  /** H08.3: a generated summary shows each bill's current situation and a link to it (the preview does not). */
  readonly showCurrent = input(false);
  readonly details = computed(() => this.summary().items.slice(0, this.summary().detailCount));
  readonly money = formatCurrency;
  readonly date = formatDate;

  channelText(channel: ReminderSummary['channels'][number]): string {
    const name = channel.channel === 'IN_APP' ? 'No aplicativo (os dois membros)' : 'WhatsApp do administrador';
    if (channel.status === 'PLANNED') return `${name}: previsto`;
    return `${name}: não será enviado (${CHANNEL_REASON_LABELS[channel.reason ?? ''] ?? channel.reason})`;
  }

  /** What changed since the summary, or null when the bill is still pending with the same due date. */
  currentNote(item: ReminderSummaryItem): string | null {
    if (!this.showCurrent()) return null;
    if (item.forecast || !item.expenseId) return null;
    if (!item.currentStatus) return 'Agora: não encontrada';
    if (item.currentStatus === 'PAID') return 'Agora: paga';
    if (item.currentStatus === 'CANCELLED') return 'Agora: cancelada';
    if (item.currentDueDate && item.currentDueDate !== item.dueDate) return `Agora vence em ${formatDate(item.currentDueDate)}`;
    return null;
  }

  plural(count: number, one: string, many: string): string {
    return `${count} ${count === 1 ? one : many}`;
  }
}
