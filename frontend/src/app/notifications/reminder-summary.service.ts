import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ReminderSlot = 'FIRST' | 'SECOND';

export interface ReminderSummaryItem {
  position: number; expenseId: string | null; recurrenceId: string | null; description: string; label: string;
  amount: string; dueDate: string; estimated: boolean; overdue: boolean; forecast: boolean; origin: string;
  installmentNumber: number | null; installmentCount: number | null;
  /** H08.3: the expense now, read at each query; null for a forecast that was never materialized. */
  currentStatus?: 'PENDING' | 'PAID' | 'CANCELLED' | null; currentDueDate?: string | null;
}

/** H08.2: amounts are decimal strings; {@code items} is the full list and the first {@code detailCount} go in the message. */
export interface ReminderSummary {
  id: string | null; date: string; slot: ReminderSlot; scheduledTime: string; timeZone: string; generatedAt: string | null;
  count: number; total: string; estimatedCount: number; estimatedTotal: string; overdueCount: number;
  detailCount: number; remaining: number; items: ReminderSummaryItem[]; link: string | null; text: string;
  channels: { channel: 'IN_APP' | 'WHATSAPP'; status: 'PLANNED' | 'SKIPPED'; reason: string | null }[];
}

export interface ReminderPreview {
  date: string; slot: ReminderSlot; scheduledTime: string; timeZone: string; today: string; summary: ReminderSummary | null;
}

export const SLOT_LABELS: Record<ReminderSlot, string> = { FIRST: 'Primeiro horário', SECOND: 'Segundo horário' };

/** H08.4, administrator only: the WhatsApp side of a summary or of a test. Acceptance is not delivery. */
export interface WhatsAppDelivery {
  state: string; stateMessage: string; kind: 'SUMMARY' | 'TEST'; reason: string | null; reasonMessage: string | null;
  recipientMasked: string | null; itemCount: number | null; createdAt: string | null; attemptedAt: string | null;
  acceptedAt: string | null; sentAt: string | null; deliveredAt: string | null; readAt: string | null; failedAt: string | null;
  attempts: { number: number; startedAt: string; finishedAt: string | null; outcome: string | null }[];
  /** H08.5: next attempt of the same summary while one waits; when an uncertain result was confirmed later. */
  nextAttemptAt: string | null; reconciledAt: string | null;
}

export const CHANNEL_REASON_LABELS: Record<string, string> = {
  RECIPIENT_REQUIRED: 'sem número cadastrado',
  CONSENT_REQUIRED: 'sem consentimento do administrador atual',
  DISABLED: 'canal desativado',
  SUSPENDED: 'canal suspenso por uma falha permanente',
  PROVIDER_UNAVAILABLE: 'envio pela Meta desligado ou incompleto no servidor',
};

@Injectable({ providedIn: 'root' })
export class ReminderSummaryService {
  private readonly http = inject(HttpClient);
  private readonly endpoint = '/api/v1/notifications/reminders';

  preview(date: string | null, slot: ReminderSlot): Observable<ReminderPreview> {
    let params = new HttpParams().set('slot', slot);
    if (date) params = params.set('date', date);
    return this.http.get<ReminderPreview>(`${this.endpoint}/preview`, { params });
  }

  summary(id: string): Observable<ReminderSummary> {
    return this.http.get<ReminderSummary>(`${this.endpoint}/summaries/${encodeURIComponent(id)}`);
  }

  /** H08.4: 403 for the guest, who never sees WhatsApp details. */
  whatsapp(id: string): Observable<WhatsAppDelivery> {
    return this.http.get<WhatsAppDelivery>(`${this.endpoint}/summaries/${encodeURIComponent(id)}/whatsapp`);
  }
}
