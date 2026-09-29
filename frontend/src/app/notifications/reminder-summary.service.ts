import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ReminderSlot = 'FIRST' | 'SECOND';

export interface ReminderSummaryItem {
  position: number; expenseId: string | null; recurrenceId: string | null; description: string; label: string;
  amount: string; dueDate: string; estimated: boolean; overdue: boolean; forecast: boolean; origin: string;
  installmentNumber: number | null; installmentCount: number | null;
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

export const CHANNEL_REASON_LABELS: Record<string, string> = {
  RECIPIENT_REQUIRED: 'sem número cadastrado',
  CONSENT_REQUIRED: 'sem consentimento do administrador atual',
  DISABLED: 'canal desativado',
  PROVIDER_UNAVAILABLE: 'envio real ainda indisponível',
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
}
