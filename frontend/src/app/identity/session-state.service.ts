import { Injectable, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { AccountAccessService, AuthenticatedUserContext } from './account-access.service';

/**
 * H09.1: who is signed in, as last confirmed by the server. It only drives the shell (menu and quick action);
 * every page keeps asking the backend, which remains the only authority on session and role.
 * `undefined` means "not checked yet", `null` means "no session".
 */
@Injectable({ providedIn: 'root' })
export class SessionStateService {
  private readonly access = inject(AccountAccessService);
  private readonly state = signal<AuthenticatedUserContext | null | undefined>(undefined);
  private pending?: Subscription;
  readonly user = this.state.asReadonly();

  refresh(): void {
    this.pending?.unsubscribe();
    this.pending = this.access.context().subscribe({
      next: context => this.state.set(context),
      error: () => this.state.set(null),
    });
  }

  signedIn(context: AuthenticatedUserContext): void {
    this.pending?.unsubscribe();
    this.state.set(context);
  }

  signedOut(): void {
    this.pending?.unsubscribe();
    this.state.set(null);
  }
}
