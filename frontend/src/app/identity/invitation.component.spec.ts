import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { AccountAccessService } from './account-access.service';
import { InvitationComponent } from './invitation.component';

describe('InvitationComponent', () => {
  let fixture: ComponentFixture<InvitationComponent>;
  const access = {
    context: vi.fn(), currentInvitation: vi.fn(), invite: vi.fn(), resendInvitation: vi.fn(),
    revokeInvitation: vi.fn(), previewInvitation: vi.fn(), acceptInvitation: vi.fn(),
    members: vi.fn(), removeMember: vi.fn(), transferAdministration: vi.fn(), leaveSpace: vi.fn(),
  };
  const router = { navigateByUrl: vi.fn() };

  async function configure(mode: 'manage' | 'accept', token = '') {
    await TestBed.configureTestingModule({
      imports: [InvitationComponent],
      providers: [
        { provide: AccountAccessService, useValue: access },
        { provide: Router, useValue: router },
        { provide: ActivatedRoute, useValue: { snapshot: {
          data: { mode }, queryParamMap: convertToParamMap(token ? { token } : {}),
        } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(InvitationComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    vi.clearAllMocks();
    access.members.mockReturnValue(of([]));
  });

  it('allows an administrator to invite a second member', async () => {
    access.context.mockReturnValue(of({ role: 'ADMINISTRATOR' }));
    access.currentInvitation.mockReturnValue(of({ pending: false, invitedEmail: null, expiresAt: null }));
    access.invite.mockReturnValue(of(void 0));
    await configure('manage');
    fixture.componentInstance.inviteForm.setValue({ email: 'guest@example.com' });

    fixture.componentInstance.invite();
    fixture.detectChanges();

    expect(access.invite).toHaveBeenCalledWith('guest@example.com');
    expect(fixture.nativeElement.textContent).toContain('Convite criado');
  });

  it('shows resend and revoke recovery for a pending invitation', async () => {
    access.context.mockReturnValue(of({ role: 'ADMINISTRATOR' }));
    access.currentInvitation.mockReturnValue(of({
      pending: true, invitedEmail: 'guest@example.com', expiresAt: '2026-10-01T15:00:00Z',
    }));
    await configure('manage');

    expect(fixture.nativeElement.textContent).toContain('guest@example.com');
    expect(fixture.nativeElement.textContent).toContain('Reenviar e substituir link');
    expect(fixture.nativeElement.textContent).toContain('Revogar convite');
  });

  it('keeps the persisted invitation recoverable after SMTP delivery failure', async () => {
    access.context.mockReturnValue(of({ role: 'ADMINISTRATOR' }));
    access.currentInvitation
      .mockReturnValueOnce(of({ pending: false, invitedEmail: null, expiresAt: null }))
      .mockReturnValueOnce(of({ pending: true, invitedEmail: 'guest@example.com', expiresAt: '2026-10-01T15:00:00Z' }));
    access.invite.mockReturnValue(throwError(() => new HttpErrorResponse({
      status: 503,
      error: { code: 'INVITATION_EMAIL_DELIVERY_FAILED', message: 'Convite salvo; reenvie.' },
    })));
    await configure('manage');
    fixture.componentInstance.inviteForm.setValue({ email: 'guest@example.com' });

    fixture.componentInstance.invite();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Convite salvo; reenvie.');
    expect(fixture.nativeElement.textContent).toContain('Reenviar e substituir link');
  });

  it('creates a new invited identity and clearly discloses full history access', async () => {
    access.previewInvitation.mockReturnValue(of({
      spaceName: 'Casa', existingAccount: false,
      loginRequired: false, authenticatedAsInvitee: false,
    }));
    access.acceptInvitation.mockReturnValue(of(void 0));
    await configure('accept', 'opaque');
    fixture.componentInstance.acceptForm.setValue({
      displayName: 'Pessoa Convidada', password: 'senha segura 2026',
    });

    fixture.componentInstance.accept();
    fixture.detectChanges();

    expect(access.acceptInvitation).toHaveBeenCalledWith(
      'opaque', 'Pessoa Convidada', 'senha segura 2026');
    expect(fixture.nativeElement.textContent).toContain('Convite aceito');
  });

  it('requires the existing recipient account to log in before acceptance', async () => {
    access.previewInvitation.mockReturnValue(of({
      spaceName: 'Casa', existingAccount: true,
      loginRequired: true, authenticatedAsInvitee: false,
    }));
    await configure('accept', 'opaque');

    expect(fixture.nativeElement.textContent).toContain('Entre com o email destinatário');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });

  it('lets the administrator remove a guest while preserving the history message', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    access.context.mockReturnValue(of({ role: 'ADMINISTRATOR' }));
    access.members.mockReturnValue(of([
      { userId: 'admin', displayName: 'Admin', email: 'admin@example.com', role: 'ADMINISTRATOR', currentUser: true },
      { userId: 'guest', displayName: 'Pessoa', email: 'guest@example.com', role: 'GUEST', currentUser: false },
    ]));
    access.currentInvitation.mockReturnValue(of({ pending: false, invitedEmail: null, expiresAt: null }));
    access.removeMember.mockReturnValue(of(void 0));
    await configure('manage');

    fixture.componentInstance.remove(fixture.componentInstance.members()[1]);
    fixture.detectChanges();

    expect(access.removeMember).toHaveBeenCalledWith('guest');
    expect(fixture.nativeElement.textContent).toContain('histórico foi preservado');
  });

  it('allows a guest to leave and returns to login', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    access.context.mockReturnValue(of({ role: 'GUEST' }));
    access.members.mockReturnValue(of([
      { userId: 'guest', displayName: 'Pessoa', email: 'guest@example.com', role: 'GUEST', currentUser: true },
    ]));
    access.leaveSpace.mockReturnValue(of(void 0));
    await configure('manage');

    fixture.componentInstance.leave();

    expect(access.leaveSpace).toHaveBeenCalled();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/entrar');
  });
});
