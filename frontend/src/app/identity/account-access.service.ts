import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { switchMap } from 'rxjs';

export interface AuthenticatedUserContext {
  userId: string;
  displayName: string;
  email: string;
  spaceId: string;
  spaceName: string;
  role: 'ADMINISTRATOR' | 'GUEST';
  currency: 'BRL';
  locale: 'pt-BR';
  timeZone: string;
}

export interface InvitationState {
  pending: boolean;
  invitedEmail: string | null;
  expiresAt: string | null;
}

export interface InvitationPreview {
  spaceName: string;
  existingAccount: boolean;
  loginRequired: boolean;
  authenticatedAsInvitee: boolean;
}

export interface SpaceMember {
  userId: string;
  displayName: string;
  email: string;
  role: 'ADMINISTRATOR' | 'GUEST';
  currentUser: boolean;
}

@Injectable({ providedIn: 'root' })
export class AccountAccessService {
  private readonly http = inject(HttpClient);
  private readonly endpoint = '/api/v1/auth';

  login(email: string, password: string) {
    return this.withCsrf(() => this.http.post<void>(`${this.endpoint}/login`, { email, password }));
  }

  context() {
    return this.http.get<AuthenticatedUserContext>('/api/v1/identity/me', {
      headers: new HttpHeaders({ 'X-User-Activity': 'true' }),
    });
  }

  logout() {
    return this.withCsrf(() => this.http.post<void>(`${this.endpoint}/logout`, {}));
  }

  logoutEverywhere() {
    return this.withCsrf(() => this.http.delete<void>(`${this.endpoint}/sessions`));
  }

  requestEmailConfirmation(email: string) {
    return this.withCsrf(() => this.http.post<{ message: string }>(
      `${this.endpoint}/email-confirmations`, { email }));
  }

  confirmEmail(token: string) {
    return this.withCsrf(() => this.http.post<void>(
      `${this.endpoint}/email-confirmations/confirm`, { token }));
  }

  requestPasswordReset(email: string) {
    return this.withCsrf(() => this.http.post<{ message: string }>(
      `${this.endpoint}/password-resets`, { email }));
  }

  resetPassword(token: string, newPassword: string) {
    return this.withCsrf(() => this.http.post<void>(
      `${this.endpoint}/password-resets/complete`, { token, newPassword }));
  }

  currentInvitation() {
    return this.http.get<InvitationState>('/api/v1/identity/invitations');
  }

  invite(email: string) {
    return this.withCsrf(() => this.http.post<void>('/api/v1/identity/invitations', { email }));
  }

  resendInvitation() {
    return this.withCsrf(() => this.http.post<void>('/api/v1/identity/invitations/resend', {}));
  }

  revokeInvitation() {
    return this.withCsrf(() => this.http.delete<void>('/api/v1/identity/invitations'));
  }

  previewInvitation(token: string) {
    return this.http.get<InvitationPreview>('/api/v1/invitations/preview', { params: { token } });
  }

  acceptInvitation(token: string, displayName?: string, password?: string) {
    return this.withCsrf(() => this.http.post<void>('/api/v1/invitations/accept', {
      token, displayName: displayName || null, password: password || null,
    }));
  }

  members() {
    return this.http.get<SpaceMember[]>('/api/v1/identity/members');
  }

  removeMember(userId: string) {
    return this.withCsrf(() => this.http.delete<void>(`/api/v1/identity/members/${userId}`));
  }

  transferAdministration(userId: string) {
    return this.withCsrf(() => this.http.post<void>(
      `/api/v1/identity/members/${userId}/administration-transfer`, {}));
  }

  leaveSpace() {
    return this.withCsrf(() => this.http.post<void>('/api/v1/identity/membership/leave', {}));
  }

  private withCsrf<T>(operation: () => import('rxjs').Observable<T>) {
    return this.http.get<{ headerName: string }>(`${this.endpoint}/csrf`).pipe(switchMap(operation));
  }
}
