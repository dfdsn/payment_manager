import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ReminderSettingsComponent } from './reminder-settings.component';
import { ReminderSettings, ReminderSettingsService } from './reminder-settings.service';

const settings = (overrides: Partial<ReminderSettings> = {}, whatsapp: Partial<ReminderSettings['whatsapp']> = {}): ReminderSettings => ({
  canManage: true, timeZone: 'America/Sao_Paulo', version: 0, updatedAt: null,
  schedule: { firstTime: '09:00', secondTime: '18:00', defaultFirstTime: '09:00', defaultSecondTime: '18:00' },
  whatsapp: {
    hasRecipient: false, recipient: null, recipientFormatted: null, recipientLastDigits: null, enabled: false,
    consent: { active: false, grantedAt: null, grantedByDisplayName: null, recipientLastDigits: null },
    provider: { available: false, code: 'PROVIDER_DISABLED', message: 'O envio real pela Meta está desligado na configuração do servidor.' },
    state: 'RECIPIENT_REQUIRED', consentTextVersion: 'WHATSAPP-RESUMOS-V1', consentText: 'Autorizo o account_Manager a enviar resumos.',
    suspension: null,
    ...whatsapp,
  },
  ...overrides,
});

const withNumber = (version = 1) => settings({ version }, {
  hasRecipient: true, recipient: '+5511987654321', recipientFormatted: '+55 11 98765-4321', recipientLastDigits: '4321',
  state: 'CONSENT_REQUIRED',
});

const consented = (version = 2, enabled = false) => settings({ version }, {
  hasRecipient: true, recipient: '+5511987654321', recipientFormatted: '+55 11 98765-4321', recipientLastDigits: '4321',
  enabled, consent: { active: true, grantedAt: '2026-09-29T12:00:00Z', grantedByDisplayName: 'Admin', recipientLastDigits: '4321' },
  state: enabled ? 'PROVIDER_UNAVAILABLE' : 'DISABLED',
});

describe('ReminderSettingsComponent', () => {
  let fixture: ComponentFixture<ReminderSettingsComponent>;
  const api = { settings: vi.fn(), events: vi.fn(), newIdempotencyKey: vi.fn(), changeSchedule: vi.fn(), changeRecipient: vi.fn(),
    grantConsent: vi.fn(), revokeConsent: vi.fn(), changeChannel: vi.fn(), sendTest: vi.fn() };
  const element = () => fixture.nativeElement as HTMLElement;
  const text = () => element().textContent!.replace(/\s+/g, ' ');
  const byTestId = (id: string) => element().querySelector<HTMLElement>(`[data-testid="${id}"]`);
  const render = async () => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
  const click = async (id: string) => { byTestId(id)!.click(); await render(); };
  const type = async (id: string, value: string) => {
    const input = byTestId(id) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await render();
  };

  async function start(initial: ReminderSettings) {
    api.settings.mockReturnValue(of(initial));
    fixture = TestBed.createComponent(ReminderSettingsComponent);
    await render();
  }

  beforeEach(async () => {
    vi.clearAllMocks();
    Object.values(api).forEach(mock => mock.mockReset());
    api.events.mockReturnValue(of({ items: [
      { type: 'RECIPIENT_CHANGED', actorDisplayName: 'Admin', occurredAt: '2026-09-29T12:00:00Z', fromVersion: 0, toVersion: 1, detail: 'nenhum -> +55 ** *****-4321' },
    ] }));
    api.newIdempotencyKey.mockReturnValueOnce('key-1').mockReturnValueOnce('key-2').mockReturnValue('key-3');
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    await TestBed.configureTestingModule({
      imports: [ReminderSettingsComponent],
      providers: [provideRouter([]), { provide: ReminderSettingsService, useValue: api }],
    }).compileComponents();
  });

  it('explains the channel and shows defaults with nothing enabled', async () => {
    await start(settings());
    expect(text()).toContain('somente para o administrador');
    expect(text()).toContain('responsável por uma conta não muda o destinatário');
    expect((byTestId('first-time') as HTMLInputElement).value).toBe('09:00');
    expect((byTestId('second-time') as HTMLInputElement).value).toBe('18:00');
    expect(byTestId('whatsapp-state')!.dataset['state']).toBe('RECIPIENT_REQUIRED');
    expect(byTestId('provider-status')!.textContent).toContain('desligado na configuração do servidor');
    expect(byTestId('recipient')!.textContent).toContain('Não cadastrado');
    expect(byTestId('start-consent')).toBeNull();
    expect(byTestId('enable-channel')).toBeNull();
    expect(text()).not.toContain('token');
  });

  it('saves the schedule with the version and blocks a second time before the first', async () => {
    await start(settings());
    await type('first-time', '18:00');
    await type('second-time', '09:00');
    expect(text()).toContain('O segundo horário precisa ser posterior ao primeiro');
    await click('save-schedule');
    expect(api.changeSchedule).not.toHaveBeenCalled();
    await type('first-time', '08:30');
    await type('second-time', '20:00');
    api.changeSchedule.mockReturnValue(of(settings({ version: 1, schedule: { firstTime: '08:30', secondTime: '20:00', defaultFirstTime: '09:00', defaultSecondTime: '18:00' } })));
    await click('save-schedule');
    expect(api.changeSchedule).toHaveBeenCalledWith(0, '08:30', '20:00', 'key-1');
    expect(byTestId('settings-success')!.textContent).toContain('08:30 e 20:00');
    expect(byTestId('settings-events')!.textContent).toContain('Número alterado por Admin');
  });

  it('saves the number, asks for explicit consent and then enables the channel', async () => {
    await start(settings());
    await type('phone', '(11) 98765-4321');
    api.changeRecipient.mockReturnValue(of(withNumber()));
    await click('save-recipient');
    expect(api.changeRecipient).toHaveBeenCalledWith(0, '(11) 98765-4321', 'key-1');
    expect(byTestId('recipient')!.textContent).toContain('+55 11 98765-4321');
    expect(byTestId('enable-channel')).toBeNull();

    await click('start-consent');
    expect(byTestId('consent-text')!.textContent).toContain('Autorizo');
    await click('grant-consent');
    expect(api.grantConsent).not.toHaveBeenCalled();
    expect(text()).toContain('Marque a caixa');
    (byTestId('consent-accept') as HTMLInputElement).click();
    await render();
    api.grantConsent.mockReturnValue(of(consented()));
    await click('grant-consent');
    expect(api.grantConsent).toHaveBeenCalledWith(1, '+5511987654321', 'key-2');
    expect(byTestId('consent-status')!.textContent).toContain('Registrado por Admin');
    expect(byTestId('consent-text')).toBeNull();

    api.changeChannel.mockReturnValue(of(consented(3, true)));
    await click('enable-channel');
    expect(api.changeChannel).toHaveBeenCalledWith(2, true, 'key-3');
    expect(byTestId('whatsapp-state')!.dataset['state']).toBe('PROVIDER_UNAVAILABLE');
    expect(byTestId('whatsapp-state')!.textContent).toContain('envio pela Meta está desligado ou incompleto');
    expect(byTestId('channel-status')!.textContent).toContain('Ativado');
  });

  it('keeps the key after a lost answer and reloads after a version conflict', async () => {
    await start(withNumber());
    api.changeChannel.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
    await click('start-consent');
    (byTestId('consent-accept') as HTMLInputElement).click();
    await render();
    api.grantConsent.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
    await click('grant-consent');
    expect(text()).toContain('Sem conexão com o servidor');
    api.grantConsent.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409,
      error: { code: 'NOTIFICATION_SETTINGS_VERSION_CONFLICT', message: 'As configurações foram alteradas.' } })));
    api.settings.mockReturnValue(of(consented(5)));
    await click('grant-consent');
    expect(api.grantConsent.mock.calls.map(call => call[2])).toEqual(['key-1', 'key-1']);
    expect(api.settings).toHaveBeenCalledTimes(2);
    expect(text()).toContain('As configurações foram alteradas.');
    expect(byTestId('consent-status')!.textContent).toContain('Registrado');
  });

  it('revokes after confirmation and disables the channel', async () => {
    await start(consented(3, true));
    api.revokeConsent.mockReturnValue(of(withNumber(4)));
    await click('revoke-consent');
    expect(window.confirm).toHaveBeenCalled();
    expect(api.revokeConsent).toHaveBeenCalledWith(3, 'key-1');
    expect(byTestId('settings-success')!.textContent).toContain('Consentimento revogado');
    api.changeChannel.mockReturnValue(of(consented(4, false)));
  });

  it('shows the guest the times and explanation without the number or controls', async () => {
    await start(settings({ canManage: false, version: 2 }, { hasRecipient: true, recipientLastDigits: '4321', state: 'CONSENT_REQUIRED' }));
    expect(byTestId('schedule-readonly')!.textContent).toContain('09:00');
    expect(byTestId('guest-whatsapp')!.textContent).toContain('terminado em 4321');
    expect(byTestId('guest-whatsapp')!.textContent).toContain('somente no aplicativo');
    expect(byTestId('save-schedule')).toBeNull();
    expect(byTestId('phone')).toBeNull();
    expect(byTestId('settings-events')).toBeNull();
    expect(api.events).not.toHaveBeenCalled();
  });

  it('reports a load failure with a retry and asks to sign in on 401', async () => {
    api.settings.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 401 })));
    fixture = TestBed.createComponent(ReminderSettingsComponent);
    await render();
    expect(text()).toContain('Entre para consultar os lembretes.');
    api.settings.mockReturnValue(of(settings()));
    element().querySelector<HTMLButtonElement>('.error-summary button')!.click();
    await render();
    expect(byTestId('whatsapp-state')).not.toBeNull();
  });
  it('sends one test to the consented number and says acceptance is not delivery (H08.4)', async () => {
    const ready = consented(3, false);
    ready.whatsapp.provider = { available: true, code: 'PROVIDER_READY', message: 'Envio pela Meta configurado.' };
    await start(ready);
    expect(byTestId('whatsapp-test')!.textContent).toContain('sem dados de contas');
    api.sendTest.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
    await click('send-test');
    expect(text()).toContain('tentar de novo não envia duas vezes');
    api.sendTest.mockReturnValueOnce(of({ state: 'ACCEPTED', stateMessage: 'Aceito pela Meta. A entrega ainda não foi confirmada.',
      kind: 'TEST', reason: null, reasonMessage: null, recipientMasked: '+55 ** *****-4321', itemCount: null, createdAt: null,
      attemptedAt: null, acceptedAt: null, sentAt: null, deliveredAt: null, readAt: null, failedAt: null, attempts: [] }));
    await click('send-test');
    expect(api.sendTest).toHaveBeenNthCalledWith(1, 'key-1');
    expect(api.sendTest).toHaveBeenNthCalledWith(2, 'key-1');
    expect(byTestId('test-result')!.dataset['state']).toBe('ACCEPTED');
    expect(byTestId('test-result')!.textContent).toContain('A entrega ainda não foi confirmada');
    expect(byTestId('test-result')!.textContent).toContain('+55 ** *****-4321');
    api.sendTest.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409,
      error: { code: 'WHATSAPP_TEST_TOO_SOON', message: 'Aguarde um minuto entre dois testes.' } })));
    await click('send-test');
    expect(api.sendTest).toHaveBeenLastCalledWith('key-2');
    expect(text()).toContain('Aguarde um minuto entre dois testes.');
  });

  it('offers no test without consent or while the provider is unavailable (H08.4)', async () => {
    await start(consented(2, true));
    expect(byTestId('whatsapp-test')).toBeNull();
    const noConsent = withNumber();
    noConsent.whatsapp.provider = { available: true, code: 'PROVIDER_READY', message: 'ok' };
    await start(noConsent);
    expect(byTestId('whatsapp-test')).toBeNull();
    await start(settings({ canManage: false }, { hasRecipient: true, recipientLastDigits: '4321',
      consent: { active: true, grantedAt: null, grantedByDisplayName: null, recipientLastDigits: '4321' },
      provider: { available: true, code: 'PROVIDER_READY', message: 'ok' }, state: 'READY' }));
    expect(byTestId('whatsapp-test')).toBeNull();
    expect(byTestId('send-test')).toBeNull();
  });

  it('shows a suspension with what to correct and lets the administrator reactivate the channel (H08.5)', async () => {
    const suspended = settings({ version: 4 }, {
      hasRecipient: true, recipient: '+5511987654321', recipientFormatted: '+55 11 98765-4321', recipientLastDigits: '4321',
      enabled: false, consent: { active: true, grantedAt: '2026-09-29T12:00:00Z', grantedByDisplayName: 'Admin', recipientLastDigits: '4321' },
      provider: { available: true, code: 'PROVIDER_READY', message: 'ok' }, state: 'SUSPENDED',
      suspension: { reason: 'RECIPIENT_INVALID', suspendedAt: '2026-10-05T12:00:10Z', message: 'Confira o número; se ele estiver certo, reative o canal.' },
    });
    api.events.mockReturnValue(of({ items: [
      { type: 'CHANNEL_SUSPENDED', actorDisplayName: 'Sistema', occurredAt: '2026-10-05T12:00:10Z', fromVersion: 3, toVersion: 4, detail: 'RECIPIENT_INVALID' },
    ] }));
    await start(suspended);
    expect(byTestId('whatsapp-state')!.dataset['state']).toBe('SUSPENDED');
    expect(byTestId('whatsapp-state')!.textContent).toContain('suspenso por uma falha permanente');
    expect(byTestId('suspension')!.textContent).toContain('05/10/2026');
    expect(byTestId('suspension')!.textContent).toContain('Confira o número');
    expect(byTestId('channel-status')!.textContent).toContain('Suspenso');
    expect(byTestId('settings-events')!.textContent).toContain('Canal suspenso automaticamente por Sistema');
    expect(byTestId('enable-channel')!.textContent).toContain('Reativar canal');
    expect(text()).toContain('Resultado incerto nunca é reenviado');
    api.changeChannel.mockReturnValue(of(settings({ version: 5 }, { ...suspended.whatsapp, enabled: true, state: 'READY', suspension: null })));
    await click('enable-channel');
    expect(api.changeChannel).toHaveBeenCalledWith(4, true, 'key-1');
    expect(byTestId('suspension')).toBeNull();
    expect(byTestId('channel-status')!.textContent).toContain('Ativado');
  });
});
