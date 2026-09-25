import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { ApiError } from './initial-setup.service';
import {
  AccountAccessService, AuthenticatedUserContext, InvitationPreview, InvitationState, SpaceMember,
} from './account-access.service';

type InvitationMode = 'manage' | 'accept';

@Component({
  selector: 'app-invitation',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  templateUrl: './invitation.component.html',
  styleUrl: './account-access.component.scss',
})
export class InvitationComponent implements OnInit {
  private readonly formBuilder = inject(FormBuilder);
  private readonly access = inject(AccountAccessService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly mode = this.route.snapshot.data['mode'] as InvitationMode;
  readonly token = this.route.snapshot.queryParamMap.get('token') ?? '';
  readonly returnUrl = `/aceitar-convite?token=${encodeURIComponent(this.token)}`;
  readonly submitting = signal(false);
  readonly message = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly context = signal<AuthenticatedUserContext | null>(null);
  readonly state = signal<InvitationState | null>(null);
  readonly preview = signal<InvitationPreview | null>(null);
  readonly members = signal<SpaceMember[]>([]);

  readonly inviteForm = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });
  readonly acceptForm = this.formBuilder.nonNullable.group({
    displayName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(100)]],
    password: ['', [Validators.required, Validators.minLength(12), Validators.maxLength(72)]],
  });

  ngOnInit(): void {
    if (this.mode === 'accept') {
      this.loadPreview();
    } else {
      this.loadManagement();
    }
  }

  invite(): void {
    if (this.inviteForm.invalid || this.submitting()) {
      this.inviteForm.markAllAsTouched();
      return;
    }
    this.start();
    this.access.invite(this.inviteForm.getRawValue().email)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.message.set('Convite criado e envio solicitado.');
          this.inviteForm.reset();
          this.refreshState();
        },
        error: error => this.handleInvitationMutationError(error, 'Não foi possível criar o convite.'),
      });
  }

  resend(): void {
    this.start();
    this.access.resendInvitation().pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => {
        this.message.set('Novo link enviado; o link anterior deixou de funcionar.');
        this.refreshState();
      },
      error: error => this.handleInvitationMutationError(error, 'Não foi possível reenviar o convite.'),
    });
  }

  revoke(): void {
    this.start();
    this.access.revokeInvitation().pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => {
        this.message.set('Convite revogado.');
        this.state.set({ pending: false, invitedEmail: null, expiresAt: null });
      },
      error: error => this.handleError(error, 'Não foi possível revogar o convite.'),
    });
  }

  remove(member: SpaceMember): void {
    if (this.submitting() || !window.confirm(`Remover ${member.displayName} deste espaço?`)) return;
    this.start();
    this.access.removeMember(member.userId).pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => {
        this.message.set('Membro removido. O histórico foi preservado e o acesso foi revogado.');
        this.loadMembers();
        this.refreshState();
      },
      error: error => this.handleError(error, 'Não foi possível remover o membro.'),
    });
  }

  transfer(member: SpaceMember): void {
    if (this.submitting() || !window.confirm(`Transferir a administração para ${member.displayName}?`)) return;
    this.start();
    this.access.transferAdministration(member.userId)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.message.set('Administração transferida. Seu perfil agora é convidado.');
          this.loadManagement();
        },
        error: error => this.handleError(error, 'Não foi possível transferir a administração.'),
      });
  }

  leave(): void {
    if (this.submitting() || !window.confirm('Sair deste espaço? Seu acesso será encerrado.')) return;
    this.start();
    this.access.leaveSpace().pipe(finalize(() => this.submitting.set(false))).subscribe({
      next: () => void this.router.navigateByUrl('/entrar'),
      error: error => this.handleError(error, 'Não foi possível sair do espaço.'),
    });
  }

  accept(): void {
    const preview = this.preview();
    if (!preview || this.submitting()) return;
    if (!preview.existingAccount && this.acceptForm.invalid) {
      this.acceptForm.markAllAsTouched();
      return;
    }
    this.start();
    const value = this.acceptForm.getRawValue();
    this.access.acceptInvitation(this.token, value.displayName, value.password)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: () => {
          this.message.set('Convite aceito. Entre para acessar o espaço compartilhado.');
          this.preview.set(null);
          this.acceptForm.reset();
        },
        error: error => this.handleError(error, 'Não foi possível aceitar o convite.'),
      });
  }

  private loadManagement(): void {
    this.access.context().subscribe({
      next: context => {
        this.context.set(context);
        this.loadMembers();
        if (context.role === 'ADMINISTRATOR') this.refreshState();
      },
      error: error => this.handleError(error, 'Entre como administrador para gerenciar membros.'),
    });
  }

  private loadMembers(): void {
    this.access.members().subscribe({
      next: members => this.members.set(members),
      error: error => this.handleError(error, 'Não foi possível consultar os membros.'),
    });
  }

  private refreshState(): void {
    this.access.currentInvitation().subscribe({
      next: state => this.state.set(state),
      error: error => this.handleError(error, 'Não foi possível consultar o convite.'),
    });
  }

  private loadPreview(): void {
    if (!this.token) {
      this.errorMessage.set('O link não contém um convite válido.');
      return;
    }
    this.access.previewInvitation(this.token).subscribe({
      next: preview => this.preview.set(preview),
      error: error => this.handleError(error, 'O convite é inválido, expirou ou foi substituído.'),
    });
  }

  private start(): void {
    this.submitting.set(true);
    this.message.set(null);
    this.errorMessage.set(null);
  }

  private handleError(error: HttpErrorResponse, fallback: string): void {
    this.submitting.set(false);
    const apiError = error.error as ApiError | undefined;
    this.errorMessage.set(apiError?.message ?? fallback);
  }

  private handleInvitationMutationError(error: HttpErrorResponse, fallback: string): void {
    this.handleError(error, fallback);
    if ((error.error as ApiError | undefined)?.code === 'INVITATION_EMAIL_DELIVERY_FAILED') {
      this.refreshState();
    }
  }
}
