import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ApiError } from '../identity/initial-setup.service';
import { formatCurrency, formatInstant } from '../reports/report.service';
import { MemberNotification, MemberNotificationService, NotificationPage, NotificationView } from './member-notification.service';

/**
 * H08.3: each member's in-app notifications. Reading, opening or dismissing only changes the member's own
 * notification: nothing is paid and the next reminders keep coming.
 */
@Component({
  selector: 'app-notification-inbox',
  imports: [RouterLink, MatCardModule, MatButtonModule, MatButtonToggleModule],
  templateUrl: './notification-inbox.component.html',
  styleUrl: './notification-inbox.component.scss',
})
export class NotificationInboxComponent implements OnInit {
  static readonly PAGE_SIZE = 20;
  private readonly api = inject(MemberNotificationService);
  private readonly router = inject(Router);
  readonly view = signal<NotificationView>('ACTIVE');
  readonly page = signal<NotificationPage | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly unauthenticated = signal(false);
  readonly actionError = signal<string | null>(null);
  readonly busy = signal<string | null>(null);
  readonly money = formatCurrency;

  ngOnInit(): void { this.load(0); }

  changeView(view: NotificationView): void {
    if (view === this.view()) return;
    this.view.set(view);
    this.load(0);
  }

  load(pageNumber: number): void {
    this.loading.set(true); this.error.set(null); this.actionError.set(null);
    this.api.list(this.view(), pageNumber, NotificationInboxComponent.PAGE_SIZE)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: page => {
          // A page emptied by dismissals goes back to the last page that still has notifications.
          if (page.items.length === 0 && page.page > 0 && page.totalPages > 0) this.load(page.totalPages - 1);
          else this.page.set(page);
        },
        error: (error: unknown) => {
          const status = error instanceof HttpErrorResponse ? error.status : 0;
          this.unauthenticated.set(status === 401);
          this.error.set(status === 401 ? 'Entre para ver seus avisos.'
            : status === 403 ? 'Você não participa mais deste espaço.'
              : 'Não foi possível carregar os avisos. Verifique a conexão e tente novamente.');
        },
      });
  }

  markRead(notification: MemberNotification): void {
    this.act(notification, this.api.read(notification.id), updated => {
      const page = this.page();
      if (!page) return;
      const wasUnread = !notification.readAt && !!updated.readAt;
      this.page.set({ ...page, unreadCount: Math.max(0, page.unreadCount - (wasUnread ? 1 : 0)),
        items: page.items.map(item => item.id === updated.id ? updated : item) });
    });
  }

  dismiss(notification: MemberNotification): void {
    this.act(notification, this.api.dismiss(notification.id), () => this.load(this.page()?.page ?? 0));
  }

  open(notification: MemberNotification): void {
    const go = () => this.router.navigate(['/lembretes/resumos', notification.summary.id]);
    if (notification.readAt) { go(); return; }
    this.busy.set(notification.id);
    this.api.read(notification.id).pipe(finalize(() => this.busy.set(null))).subscribe({ next: go, error: go });
  }

  when(notification: MemberNotification): string { return formatInstant(notification.createdAt, notification.summary.timeZone); }

  private act(notification: MemberNotification, request: ReturnType<MemberNotificationService['read']>,
              done: (updated: MemberNotification) => void): void {
    this.busy.set(notification.id); this.actionError.set(null);
    request.pipe(finalize(() => this.busy.set(null))).subscribe({
      next: updated => done(updated),
      error: (error: unknown) => {
        const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
        this.actionError.set(body?.message ?? 'Não foi possível atualizar o aviso. Tente novamente.');
      },
    });
  }
}
