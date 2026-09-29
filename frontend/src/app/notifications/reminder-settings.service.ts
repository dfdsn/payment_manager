import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, switchMap } from 'rxjs';

export type WhatsAppState = 'RECIPIENT_REQUIRED' | 'CONSENT_REQUIRED' | 'DISABLED' | 'PROVIDER_UNAVAILABLE' | 'READY';

/** H08.1: the full number only reaches the administrator; there is no credential field anywhere. */
export interface ReminderSettings {
  canManage: boolean;
  timeZone: string;
  version: number;
  updatedAt: string | null;
  schedule: { firstTime: string; secondTime: string; defaultFirstTime: string; defaultSecondTime: string };
  whatsapp: {
    hasRecipient: boolean; recipient: string | null; recipientFormatted: string | null; recipientLastDigits: string | null;
    enabled: boolean;
    consent: { active: boolean; grantedAt: string | null; grantedByDisplayName: string | null; recipientLastDigits: string | null };
    provider: { available: boolean; code: string; message: string };
    state: WhatsAppState; consentTextVersion: string; consentText: string;
  };
}

export interface ReminderSettingsEvent {
  type: string; actorDisplayName: string; occurredAt: string; fromVersion: number; toVersion: number; detail: string;
}

@Injectable({ providedIn: 'root' })
export class ReminderSettingsService {
  private readonly http = inject(HttpClient);
  private readonly endpoint = '/api/v1/notifications/settings';

  settings() { return this.http.get<ReminderSettings>(this.endpoint); }

  events() { return this.http.get<{ items: ReminderSettingsEvent[] }>(`${this.endpoint}/events`); }

  newIdempotencyKey() { return crypto.randomUUID(); }

  /** The same key must be reused when the same change is sent again after a lost answer. */
  changeSchedule(expectedVersion: number, firstTime: string, secondTime: string, key: string) {
    return this.write(key, headers => this.http.put<ReminderSettings>(`${this.endpoint}/schedule`,
      { expectedVersion, firstTime, secondTime }, { headers }));
  }

  changeRecipient(expectedVersion: number, phone: string, key: string) {
    return this.write(key, headers => this.http.put<ReminderSettings>(`${this.endpoint}/whatsapp/recipient`,
      { expectedVersion, phone }, { headers }));
  }

  grantConsent(expectedVersion: number, phone: string, key: string) {
    return this.write(key, headers => this.http.post<ReminderSettings>(`${this.endpoint}/whatsapp/consent`,
      { expectedVersion, phone, accepted: true }, { headers }));
  }

  revokeConsent(expectedVersion: number, key: string) {
    return this.write(key, headers => this.http.post<ReminderSettings>(`${this.endpoint}/whatsapp/consent/revocation`,
      { expectedVersion }, { headers }));
  }

  changeChannel(expectedVersion: number, enabled: boolean, key: string) {
    return this.write(key, headers => this.http.put<ReminderSettings>(`${this.endpoint}/whatsapp/channel`,
      { expectedVersion, enabled }, { headers }));
  }

  private write(key: string, request: (headers: HttpHeaders) => Observable<ReminderSettings>) {
    return this.http.get('/api/v1/auth/csrf').pipe(switchMap(() => request(new HttpHeaders({ 'Idempotency-Key': key }))));
  }
}

export const WHATSAPP_STATE_LABELS: Record<WhatsAppState, string> = {
  RECIPIENT_REQUIRED: 'Sem número cadastrado: nenhum resumo vai para o WhatsApp.',
  CONSENT_REQUIRED: 'Número cadastrado, falta o consentimento: nenhum resumo vai para o WhatsApp.',
  DISABLED: 'Consentimento registrado, canal desativado: nenhum resumo vai para o WhatsApp.',
  PROVIDER_UNAVAILABLE: 'Canal configurado e ativo, mas o envio real ainda não está disponível: nenhum resumo é enviado.',
  READY: 'Canal ativo: os resumos serão enviados ao número cadastrado.',
};

export const SETTINGS_EVENT_LABELS: Record<string, string> = {
  SCHEDULE_CHANGED: 'Horários alterados', RECIPIENT_CHANGED: 'Número alterado', CONSENT_GRANTED: 'Consentimento registrado',
  CONSENT_REVOKED: 'Consentimento revogado', CHANNEL_ENABLED: 'Canal ativado', CHANNEL_DISABLED: 'Canal desativado',
};
