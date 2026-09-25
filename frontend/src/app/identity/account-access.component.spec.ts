import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
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

  beforeEach(() => vi.clearAllMocks());

  it('logs in and presents the persisted space context', async () => {
    access.login.mockReturnValue(of(void 0));
    access.context.mockReturnValue(of({
      userId: 'u', displayName: 'Diego', email: 'admin@example.com',
      spaceId: 's', spaceName: 'Minha casa', role: 'ADMINISTRATOR',
      currency: 'BRL', locale: 'pt-BR', timeZone: 'America/Sao_Paulo',
    }));
    await configure('login');
    fixture.componentInstance.loginForm.setValue({ email: 'admin@example.com', password: 'frase segura 2026' });

    fixture.componentInstance.submitLogin();
    fixture.detectChanges();

    expect(access.login).toHaveBeenCalledWith('admin@example.com', 'frase segura 2026');
    expect(fixture.nativeElement.textContent).toContain('Minha casa');
    expect(fixture.nativeElement.textContent).toContain('Encerrar todas as sessões');
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
