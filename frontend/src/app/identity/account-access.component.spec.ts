import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { SessionStateService } from './session-state.service';
import { AccountAccessComponent } from './account-access.component';
import { AccountAccessService } from './account-access.service';

describe('AccountAccessComponent', () => {
  let fixture: ComponentFixture<AccountAccessComponent>;
  const access = {
    login: vi.fn(),
    context: vi.fn(),
    logout: vi.fn(),
    logoutEverywhere: vi.fn(),
    requestEmailConfirmation: vi.fn(),
    confirmEmail: vi.fn(),
    requestPasswordReset: vi.fn(),
    resetPassword: vi.fn(),
  };

  async function configure(mode: string, token = '') {
    await TestBed.configureTestingModule({
      imports: [AccountAccessComponent],
      providers: [
        { provide: AccountAccessService, useValue: access },
        { provide: ActivatedRoute, useValue: { snapshot: {
          data: { mode }, queryParamMap: convertToParamMap(token ? { token } : {}),
        } } },
        { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AccountAccessComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    vi.clearAllMocks();
    access.context.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));
  });

  it('logs in and presents the persisted space context', async () => {
    access.login.mockReturnValue(of(void 0));
    await configure('login');
    expect(fixture.nativeElement.querySelector('input[type="password"]')).not.toBeNull();
    access.context.mockReturnValue(of({
      userId: 'u', displayName: 'Diego', email: 'admin@example.com',
      spaceId: 's', spaceName: 'Minha casa', role: 'ADMINISTRATOR',
      currency: 'BRL', locale: 'pt-BR', timeZone: 'America/Sao_Paulo',
    }));
    fixture.componentInstance.loginForm.setValue({ email: 'admin@example.com', password: 'frase segura 2026' });

    fixture.componentInstance.submitLogin();
    fixture.detectChanges();

    expect(access.login).toHaveBeenCalledWith('admin@example.com', 'frase segura 2026');
    expect(fixture.nativeElement.textContent).toContain('Minha casa');
    expect(fixture.nativeElement.textContent).toContain('Encerrar todas as sessões');
    expect(TestBed.inject(SessionStateService).user()?.spaceName).toBe('Minha casa');
  });

  it('H09.1 opens the installed app on the summary of an existing session, without asking to log in again', async () => {
    access.context.mockReturnValue(of({
      userId: 'u', displayName: 'Diego', email: 'admin@example.com',
      spaceId: 's', spaceName: 'Minha casa', role: 'GUEST',
      currency: 'BRL', locale: 'pt-BR', timeZone: 'America/Sao_Paulo',
    }));
    await configure('login');

    expect(access.login).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('Olá, Diego');
    expect(fixture.nativeElement.textContent).toContain('como convidado');
    expect(fixture.nativeElement.textContent).toContain('Painel por vencimento');
    expect(fixture.nativeElement.querySelector('input[type="password"]')).toBeNull();
    expect(TestBed.inject(SessionStateService).user()?.displayName).toBe('Diego');
  });

  it('H09.1 keeps the login form, without an error, when there is no session or no connection', async () => {
    access.context.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    await configure('login');

    expect(fixture.nativeElement.querySelector('input[type="password"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
  });

  it('shows the same recovery request result to the user', async () => {
    access.requestPasswordReset.mockReturnValue(of({ message: 'Se a conta estiver disponível, enviaremos as instruções.' }));
    await configure('forgot');
    fixture.componentInstance.emailForm.setValue({ email: 'person@example.com' });

    fixture.componentInstance.submitEmailRequest();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Se a conta estiver disponível');
  });

  it('requires a token before showing the password reset form', async () => {
    await configure('reset');
    expect(fixture.nativeElement.textContent).toContain('O link não contém um token válido');
    expect(fixture.nativeElement.querySelector('form')).toBeNull();
  });
});
