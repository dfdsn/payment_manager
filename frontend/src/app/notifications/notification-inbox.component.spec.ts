import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { MemberNotification, MemberNotificationService, NotificationPage } from './member-notification.service';
import { NotificationInboxComponent } from './notification-inbox.component';

const notice = (id: string, overrides: Partial<MemberNotification> = {}): MemberNotification => ({
  id, type: 'REMINDER_SUMMARY', title: 'Contas a pagar: primeiro horário de 05/10, 09:00', message: '7 contas, total R$ 1.349,91',
  createdAt: '2026-10-05T12:00:05Z', readAt: null, dismissedAt: null,
  summary: { id: `s-${id}`, date: '2026-10-05', slot: 'FIRST', scheduledTime: '09:00', timeZone: 'America/Sao_Paulo', count: 7,
    total: '1349.91', estimatedCount: 0, estimatedTotal: '0.00', overdueCount: 1, remaining: 2, link: 'https://x/lembretes/resumos/s' },
  failure: null, ...overrides,
});

const page = (items: MemberNotification[], overrides: Partial<NotificationPage> = {}): NotificationPage => ({
  items, page: 0, size: 20, totalItems: items.length, totalPages: items.length ? 1 : 0,
  unreadCount: items.filter(item => !item.readAt).length, view: 'ACTIVE', ...overrides,
});

describe('Notification inbox (H08.3)', () => {
  const api = { list: vi.fn(), unreadCount: vi.fn(), read: vi.fn(), dismiss: vi.fn() };
  const render = async (fixture: ComponentFixture<unknown>) => { fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges(); };
  const all = (fixture: ComponentFixture<unknown>, id: string) =>
    Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(`[data-testid="${id}"]`));
  const one = (fixture: ComponentFixture<unknown>, id: string) => all(fixture, id)[0] ?? null;

  beforeEach(async () => {
    Object.values(api).forEach(mock => mock.mockReset());
    await TestBed.configureTestingModule({
      imports: [NotificationInboxComponent],
      providers: [provideRouter([]), { provide: MemberNotificationService, useValue: api }],
    }).compileComponents();
  });

  it('lists the notifications newest first with the unread marker and explains that nothing is paid', async () => {
    api.list.mockReturnValue(of(page([notice('n2'), notice('n1', { readAt: '2026-10-05T13:00:00Z' })])));
    const fixture = TestBed.createComponent(NotificationInboxComponent);
    await render(fixture);
    expect(api.list).toHaveBeenCalledWith('ACTIVE', 0, 20);
    expect(all(fixture, 'inbox-item').length).toBe(2);
    expect(all(fixture, 'inbox-new').length).toBe(1);
    expect(one(fixture, 'inbox-unread')!.textContent).toContain('1 não lido');
    expect(one(fixture, 'inbox-explain')!.textContent).toContain('nenhuma conta é paga');
    expect(all(fixture, 'inbox-read').length).toBe(1);
  });

  it('marks as read in place and dismisses by reloading the page', async () => {
    api.list.mockReturnValue(of(page([notice('n1')])));
    api.read.mockReturnValue(of(notice('n1', { readAt: '2026-10-05T13:00:00Z' })));
    const fixture = TestBed.createComponent(NotificationInboxComponent);
    await render(fixture);
    one(fixture, 'inbox-read')!.click();
    await render(fixture);
    expect(api.read).toHaveBeenCalledWith('n1');
    expect(all(fixture, 'inbox-new').length).toBe(0);
    expect(one(fixture, 'inbox-unread')!.textContent).toContain('0 não lidos');
    api.dismiss.mockReturnValue(of(notice('n1', { readAt: '2026-10-05T13:00:00Z', dismissedAt: '2026-10-05T13:01:00Z' })));
    api.list.mockReturnValue(of(page([])));
    one(fixture, 'inbox-dismiss')!.click();
    await render(fixture);
    expect(api.dismiss).toHaveBeenCalledWith('n1');
    expect(one(fixture, 'inbox-empty')!.textContent).toContain('Nenhum aviso ativo');
  });

  it('opens the summary after marking the notification as read', async () => {
    api.list.mockReturnValue(of(page([notice('n1')])));
    api.read.mockReturnValue(of(notice('n1', { readAt: '2026-10-05T13:00:00Z' })));
    const fixture = TestBed.createComponent(NotificationInboxComponent);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    await render(fixture);
    one(fixture, 'inbox-open')!.click();
    await render(fixture);
    expect(api.read).toHaveBeenCalledWith('n1');
    expect(navigate).toHaveBeenCalledWith(['/lembretes/resumos', 's-n1']);
  });

  it('shows an administrator failure with its catalog message and paginates on the server', async () => {
    const failure = notice('f1', { type: 'WHATSAPP_DELIVERY_FAILURE', title: 'WhatsApp não enviado: resumo de 05/10, 09:00',
      message: 'O envio pelo WhatsApp não está disponível no momento.', failure: { code: 'PROVIDER_UNAVAILABLE', message: 'x' } });
    api.list.mockReturnValueOnce(of(page([failure], { totalItems: 25, totalPages: 2 })));
    api.list.mockReturnValueOnce(of(page([notice('n5')], { page: 1, totalItems: 25, totalPages: 2 })));
    const fixture = TestBed.createComponent(NotificationInboxComponent);
    await render(fixture);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Somente administrador');
    expect(one(fixture, 'inbox-page')!.textContent).toContain('Página 1 de 2 · 25 avisos');
    one(fixture, 'inbox-next')!.click();
    await render(fixture);
    expect(api.list).toHaveBeenLastCalledWith('ACTIVE', 1, 20);
    expect(one(fixture, 'inbox-page')!.textContent).toContain('Página 2 de 2');
  });

  it('switches to the dismissed notifications', async () => {
    api.list.mockReturnValueOnce(of(page([])));
    api.list.mockReturnValueOnce(of(page([notice('d1', { readAt: 'x', dismissedAt: '2026-10-05T13:00:00Z' })], { view: 'DISMISSED' })));
    const fixture = TestBed.createComponent(NotificationInboxComponent);
    await render(fixture);
    fixture.componentInstance.changeView('DISMISSED');
    await render(fixture);
    expect(api.list).toHaveBeenLastCalledWith('DISMISSED', 0, 20);
    expect(all(fixture, 'inbox-dismiss').length).toBe(0);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('dispensado');
  });

  it('shows loading, errors and a retry', async () => {
    api.list.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));
    const fixture = TestBed.createComponent(NotificationInboxComponent);
    await render(fixture);
    expect(one(fixture, 'inbox-error')!.textContent).toContain('Não foi possível carregar os avisos');
    api.list.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 401 })));
    fixture.componentInstance.load(0);
    await render(fixture);
    expect(one(fixture, 'inbox-error')!.textContent).toContain('Entre para ver seus avisos');
    api.list.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 403 })));
    fixture.componentInstance.load(0);
    await render(fixture);
    expect(one(fixture, 'inbox-error')!.textContent).toContain('não participa mais');
    fixture.componentInstance.page.set(null);
    fixture.componentInstance.error.set(null);
    fixture.componentInstance.loading.set(true);
    fixture.detectChanges();
    expect(one(fixture, 'inbox-loading')).not.toBeNull();
  });

  it('keeps the list when an action fails', async () => {
    api.list.mockReturnValue(of(page([notice('n1')])));
    api.dismiss.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 404, error: { message: 'Aviso não encontrado.' } })));
    const fixture = TestBed.createComponent(NotificationInboxComponent);
    await render(fixture);
    one(fixture, 'inbox-dismiss')!.click();
    await render(fixture);
    expect(one(fixture, 'inbox-action-error')!.textContent).toContain('Aviso não encontrado.');
    expect(all(fixture, 'inbox-item').length).toBe(1);
  });
});
