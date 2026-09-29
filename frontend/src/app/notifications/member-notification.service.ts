import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ReminderSlot } from './reminder-summary.service';

export type NotificationView = 'ACTIVE' | 'DISMISSED';

/** H08.3: one member's notification. {@code summary} is the historical content of the slot; amounts are decimal strings. */
export interface MemberNotification {
  id: string; type: 'REMINDER_SUMMARY' | 'WHATSAPP_DELIVERY_FAILURE'; title: string; message: string;
  createdAt: string; readAt: string | null; dismissedAt: string | null;
  summary: {
    id: string; date: string; slot: ReminderSlot; scheduledTime: string; timeZone: string; count: number; total: string;
    estimatedCount: number; estimatedTotal: string; overdueCount: number; remaining: number; link: string;
  };
  /** Only for the administrator's WhatsApp failures: a catalog message, never a provider response. */
  failure: { code: string; message: string } | null;
}

export interface NotificationPage {
  items: MemberNotification[]; page: number; size: number; totalItems: number; totalPages: number;
  unreadCount: number; view: NotificationView;
}

@Injectable({ providedIn: 'root' })
export class MemberNotificationService {
  private readonly http = inject(HttpClient);
  private readonly endpoint = '/api/v1/notifications/inbox';

  list(view: NotificationView, page: number, size: number): Observable<NotificationPage> {
    const params = new HttpParams().set('view', view).set('page', page).set('size', size);
    return this.http.get<NotificationPage>(this.endpoint, { params });
  }

  unreadCount(): Observable<{ unreadCount: number }> {
    return this.http.get<{ unreadCount: number }>(`${this.endpoint}/unread-count`);
  }

  read(id: string): Observable<MemberNotification> {
    return this.http.post<MemberNotification>(`${this.endpoint}/${encodeURIComponent(id)}/read`, {});
  }

  dismiss(id: string): Observable<MemberNotification> {
    return this.http.post<MemberNotification>(`${this.endpoint}/${encodeURIComponent(id)}/dismiss`, {});
  }
}
