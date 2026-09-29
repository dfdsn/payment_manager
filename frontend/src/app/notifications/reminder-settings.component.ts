import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiError } from '../identity/initial-setup.service';
import { formatInstant } from '../reports/report.service';
import { WhatsAppDelivery } from './reminder-summary.service';
import {
  ReminderSettings, ReminderSettingsEvent, ReminderSettingsService, SETTINGS_EVENT_LABELS, WHATSAPP_STATE_LABELS,
} from './reminder-settings.service';

type Action = 'schedule' | 'recipient' | 'consent' | 'revoke' | 'enable' | 'disable' | 'test';

/** The server moved on since the page was loaded: show its current state before asking again. */
const RELOAD_CODES = new Set(['NOTIFICATION_SETTINGS_VERSION_CONFLICT', 'WHATSAPP_RECIPIENT_MISMATCH',
  'WHATSAPP_CONSENT_NOT_ACTIVE', 'NOTIFICATION_ADMINISTRATOR_REQUIRED', 'WHATSAPP_CONSENT_REQUIRED',
  'WHATSAPP_RECIPIENT_REQUIRED']);

/**
 * H08.1: reminder times of the space and the administrator's WhatsApp channel. The guest reads the explanation and
 * the times; only the administrator sees the number and the controls. Consent is a separate, explicit step; the
 * channel state says plainly whether anything can actually be sent.
 */
@Component({
  selector: 'app-reminder-settings',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  templateUrl: './reminder-settings.component.html',
  styleUrl: './reminder-settings.component.scss',
})
export class ReminderSettingsComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(ReminderSettingsService);
  readonly settings = signal<ReminderSettings | null>(null);
  readonly events = signal<ReminderSettingsEvent[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly success = signal<string | null>(null);
  readonly busy = signal<Action | null>(null);
  readonly consenting = signal(false);
  readonly testResult = signal<WhatsAppDelivery | null>(null);
  readonly testing = signal(false);
  readonly stateLabels = WHATSAPP_STATE_LABELS;
  readonly eventLabels = SETTINGS_EVENT_LABELS;
  readonly scheduleForm = this.fb.nonNullable.group({
    firstTime: ['09:00', [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)]],
    secondTime: ['18:00', [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)]],
  });
  readonly recipientForm = this.fb.nonNullable.group({
    phone: ['', [Validators.required, Validators.pattern(/^[+\d\s().-]{10,20}$/)]],
  });
  readonly consentForm = this.fb.nonNullable.group({ accepted: false });
  /** One key per pending intention, kept while the same change is retried after a lost answer. */
  private readonly keys = new Map<Action, string>();

  ngOnInit(): void { this.load(); }

  formatInstant(value: string): string { return formatInstant(value, this.settings()?.timeZone ?? 'America/Sao_Paulo'); }

  scheduleOrderInvalid(): boolean {
    const { firstTime, secondTime } = this.scheduleForm.getRawValue();
    return this.scheduleForm.valid && secondTime <= firstTime;
  }

  saveSchedule(): void {
    const settings = this.settings();
    if (!settings || this.scheduleForm.invalid || this.scheduleOrderInvalid()) { this.scheduleForm.markAllAsTouched(); return; }
    const { firstTime, secondTime } = this.scheduleForm.getRawValue();
    this.run('schedule', key => this.api.changeSchedule(settings.version, firstTime, secondTime, key),
      `Horários salvos: ${firstTime} e ${secondTime}.`);
  }

  saveRecipient(): void {
    const settings = this.settings();
    if (!settings || this.recipientForm.invalid) { this.recipientForm.markAllAsTouched(); return; }
    const hadConsent = settings.whatsapp.consent.active;
    this.run('recipient', key => this.api.changeRecipient(settings.version, this.recipientForm.getRawValue().phone, key),
      hadConsent ? 'Número salvo. O consentimento anterior foi revogado; autorize o novo número.' : 'Número salvo.');
  }

  startConsent(): void {
    this.consentForm.reset({ accepted: false });
    this.consenting.set(true);
  }

  cancelConsent(): void {
    this.keys.delete('consent');
    this.consenting.set(false);
  }

  grantConsent(): void {
    const settings = this.settings();
    if (!settings?.whatsapp.recipient) return;
    if (!this.consentForm.getRawValue().accepted) {
      this.error.set('Marque a caixa para autorizar o recebimento pelo WhatsApp.');
      return;
    }
    const phone = settings.whatsapp.recipient;
    this.run('consent', key => this.api.grantConsent(settings.version, phone, key), 'Consentimento registrado.',
      () => this.consenting.set(false));
  }

  revokeConsent(): void {
    const settings = this.settings();
    if (!settings || !window.confirm('Revogar o consentimento? O canal WhatsApp será desativado.')) return;
    this.run('revoke', key => this.api.revokeConsent(settings.version, key), 'Consentimento revogado e canal desativado.');
  }

  setChannel(enabled: boolean): void {
    const settings = this.settings();
    if (!settings) return;
    this.run(enabled ? 'enable' : 'disable', key => this.api.changeChannel(settings.version, enabled, key),
      enabled ? 'Canal WhatsApp ativado.' : 'Canal WhatsApp desativado. Número e consentimento continuam salvos.');
  }

  /** H08.4: one test message to the consented number; the key is kept while the answer was lost. */
  sendTest(): void {
    if (this.testing() || this.busy()) return;
    const key = this.keys.get('test') ?? this.api.newIdempotencyKey();
    this.keys.set('test', key);
    this.testing.set(true);
    this.error.set(null);
    this.success.set(null);
    this.testResult.set(null);
    this.api.sendTest(key).subscribe({
      next: result => { this.keys.delete('test'); this.testing.set(false); this.testResult.set(result); },
      error: (error: unknown) => {
        this.testing.set(false);
        if (error instanceof HttpErrorResponse && error.status === 0) {
          this.error.set('Sem conexão com o servidor. Não sabemos se o teste saiu; tentar de novo não envia duas vezes.');
          return;
        }
        this.keys.delete('test');
        const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
        this.error.set(body?.message ?? 'Não foi possível enviar o teste.');
      },
    });
  }

  reload(): void { this.load(); }

  private run(action: Action, request: (key: string) => Observable<ReminderSettings>, message: string, done?: () => void): void {
    if (this.busy()) return;
    const key = this.keys.get(action) ?? this.api.newIdempotencyKey();
    this.keys.set(action, key);
    this.busy.set(action);
    this.error.set(null);
    this.success.set(null);
    request(key).subscribe({
      next: settings => {
        this.keys.delete(action);
        this.busy.set(null);
        this.apply(settings);
        this.success.set(message);
        done?.();
        this.loadEvents(settings);
      },
      error: (error: unknown) => {
        this.busy.set(null);
        if (error instanceof HttpErrorResponse && error.status === 0) {
          // Keep the key: sending again repeats the same request instead of applying it twice.
          this.error.set('Sem conexão com o servidor. Nada foi confirmado; tente novamente.');
          return;
        }
        this.keys.delete(action);
        const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
        this.error.set(body?.message ?? 'Não foi possível salvar. Tente novamente.');
        if (body?.code && RELOAD_CODES.has(body.code)) { this.consenting.set(false); this.load(body.message); }
      },
    });
  }

  private load(notice: string | null = null): void {
    this.loading.set(true);
    this.api.settings().subscribe({
      next: settings => {
        this.loading.set(false);
        this.apply(settings);
        if (notice) this.error.set(notice);
        this.loadEvents(settings);
      },
      error: (error: unknown) => {
        this.loading.set(false);
        this.settings.set(null);
        const body = error instanceof HttpErrorResponse ? error.error as ApiError | null : null;
        this.error.set(error instanceof HttpErrorResponse && error.status === 401
          ? 'Entre para consultar os lembretes.'
          : body?.message ?? 'Não foi possível carregar as configurações. Verifique a conexão e tente novamente.');
      },
    });
  }

  private apply(settings: ReminderSettings): void {
    this.settings.set(settings);
    this.scheduleForm.reset({ firstTime: settings.schedule.firstTime, secondTime: settings.schedule.secondTime });
    this.recipientForm.reset({ phone: settings.whatsapp.recipientFormatted ?? '' });
  }

  private loadEvents(settings: ReminderSettings): void {
    if (!settings.canManage) { this.events.set([]); return; }
    this.api.events().subscribe({ next: list => this.events.set(list.items), error: () => this.events.set([]) });
  }
}
