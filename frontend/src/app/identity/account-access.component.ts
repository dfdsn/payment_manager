import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize, switchMap } from 'rxjs';
import { ApiError } from './initial-setup.service';
import { AccountAccessService, AuthenticatedUserContext } from './account-access.service';

type AccessMode = 'login' | 'confirm' | 'forgot' | 'reset';

@Component({
  selector: 'app-account-access',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  templateUrl: './account-access.component.html',
  styleUrl: './account-access.component.scss',
})
export class AccountAccessComponent {
  private readonly formBuilder = inject(FormBuilder);
  private readonly access = inject(AccountAccessService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly mode = this.route.snapshot.data['mode'] as AccessMode;
  readonly token = this.route.snapshot.queryParamMap.get('token') ?? '';
  readonly returnUrl = this.safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
  readonly submitting = signal(false);
  readonly message = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly context = signal<AuthenticatedUserContext | null>(null);

  readonly loginForm = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });
  readonly emailForm = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });
  readonly resetForm = this.formBuilder.nonNullable.group({
    newPassword: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(72)]],
  });

  submitLogin(): void {
    if (this.loginForm.invalid || this.submitting()) {
      this.loginForm.markAllAsTouched();
      return;
    }
    this.start();
    const value = this.loginForm.getRawValue();
    this.access.login(value.email, value.password).pipe(
      switchMap(() => this.access.context()),
      finalize(() => this.submitting.set(false)),
    ).subscribe({
      next: context => {
        this.context.set(context);
        this.loginForm.reset();
        if (this.returnUrl) {
          void this.router.navigateByUrl(this.returnUrl);
        }
      },
      error: error => this.handleError(error, 'Não foi possível entrar.'),
    });
  }

  submitEmailRequest(): void {
    if (this.emailForm.invalid || this.submitting()) {
      this.emailForm.markAllAsTouched();
      return;
    }
    this.start();
    const email = this.emailForm.getRawValue().email;
    const request = this.mode === 'confirm'
      ? this.access.requestEmailConfirmation(email)
      : this.access.requestPasswordReset(email);
    request.pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: response => {
        this.message.set(response.message);
        this.emailForm.reset();
      },
      error: error => this.handleError(error, 'Não foi possível solicitar as instruções.'),
    });
  }

  confirmEmail(): void {
    if (!this.token || this.submitting()) return;
    this.start();
    this.access.confirmEmail(this.token).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => this.message.set('Email confirmado. Agora você já pode entrar.'),
      error: error => this.handleError(error, 'O link de confirmação não é válido.'),
    });
  }

  resetPassword(): void {
    if (!this.token || this.resetForm.invalid || this.submitting()) {
      this.resetForm.markAllAsTouched();
      return;
    }
    this.start();
    this.access.resetPassword(this.token, this.resetForm.getRawValue().newPassword)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.message.set('Senha redefinida e sessões anteriores encerradas. Entre novamente.');
          this.resetForm.reset();
        },
        error: error => this.handleError(error, 'O link de recuperação não é válido.'),
      });
  }

  logout(everywhere: boolean): void {
    this.start();
    const action = everywhere ? this.access.logoutEverywhere() : this.access.logout();
    action.pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => {
        this.context.set(null);
        void this.router.navigate(['/entrar']);
      },
      error: error => this.handleError(error, 'Não foi possível encerrar a sessão.'),
    });
  }

  private start(): void {
    this.submitting.set(true);
    this.message.set(null);
    this.errorMessage.set(null);
  }

  private handleError(error: HttpErrorResponse, fallback: string): void {
    const apiError = error.error as ApiError | undefined;
    this.errorMessage.set(apiError?.message ?? fallback);
  }

  private safeReturnUrl(value: string | null): string | null {
    return value?.startsWith('/') && !value.startsWith('//') ? value : null;
  }
}
