import { BreakpointObserver, BreakpointState } from '@angular/cdk/layout';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { SwUpdate, VersionEvent } from '@angular/service-worker';
import { BehaviorSubject, Subject } from 'rxjs';
import { App } from './app';
import { AuthenticatedUserContext } from './identity/account-access.service';
import { SessionStateService } from './identity/session-state.service';
import { NAVIGATION, isPublicFlow } from './shell/navigation';

@Component({ template: '<p>tela</p>' })
class Screen {}

const DIEGO = { userId: 'u', displayName: 'Diego', role: 'ADMINISTRATOR' } as AuthenticatedUserContext;

describe('App shell (H09.1)', () => {
  let user: ReturnType<typeof signal<AuthenticatedUserContext | null | undefined>>;
  let layout: BehaviorSubject<BreakpointState>;
  let versionUpdates: Subject<VersionEvent>;
  let unrecoverable: Subject<{ type: 'UNRECOVERABLE_STATE'; reason: string }>;
  let refresh: ReturnType<typeof vi.fn>;

  async function start(options: { compact?: boolean; serviceWorker?: boolean; url?: string } = {}) {
    user = signal<AuthenticatedUserContext | null | undefined>(DIEGO);
    refresh = vi.fn();
    layout = new BehaviorSubject<BreakpointState>({ matches: !!options.compact, breakpoints: {} });
    versionUpdates = new Subject();
    unrecoverable = new Subject();
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([{ path: '**', component: Screen }]),
        { provide: SessionStateService, useValue: { user, refresh } },
        { provide: BreakpointObserver, useValue: { observe: () => layout } },
        ...(options.serviceWorker === false ? [] : [{ provide: SwUpdate, useValue: {
          isEnabled: true, versionUpdates, unrecoverable,
        } }]),
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl(options.url ?? '/painel');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const nav = (element: HTMLElement) => element.querySelector('[data-testid="app-navigation"]');

  it('keeps the skip link and the brand', async () => {
    const fixture = await start();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.brand')?.textContent).toContain('account_Manager');
    expect(compiled.querySelector('.skip-link')?.getAttribute('href')).toBe('#main-content');
  });

  it('offers every section and the quick action to a signed-in member, marking the current one', async () => {
    const fixture = await start({ url: '/pagamentos' });
    const element = fixture.nativeElement as HTMLElement;
    const links = Array.from(nav(element)!.querySelectorAll('a'));
    expect(links.map(link => link.getAttribute('href'))).toEqual(NAVIGATION.map(item => item.path));
    const current = links.filter(link => link.getAttribute('aria-current') === 'page');
    expect(current.map(link => link.getAttribute('href'))).toEqual(['/pagamentos']);
    expect(current[0].classList).toContain('active');
    const quickAdd = element.querySelector('[data-testid="quick-add"]')!;
    expect(quickAdd.textContent).toContain('Adicionar despesa');
    expect(quickAdd.getAttribute('href')).toBe('/despesas?nova=1');
    expect(refresh).toHaveBeenCalled();
  });

  it('shows no menu or quick action without a session or while it is unknown', async () => {
    const fixture = await start();
    user.set(null);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    expect(nav(element)).toBeNull();
    expect(element.querySelector('[data-testid="quick-add"]')).toBeNull();
    user.set(undefined);
    fixture.detectChanges();
    expect(nav(element)).toBeNull();
  });

  it('never shows the menu inside the sign-in, recovery, invitation and setup flows', async () => {
    const fixture = await start({ url: '/aceitar-convite?token=abc' });
    expect(nav(fixture.nativeElement)).toBeNull();
    expect(isPublicFlow('/redefinir-senha?token=x')).toBe(true);
    expect(isPublicFlow('/configuracao-inicial')).toBe(true);
    expect(isPublicFlow('/entrar')).toBe(false);
    expect(isPublicFlow('/despesas?nova=1')).toBe(false);
  });

  it('on a phone opens the menu from a labelled button and moves focus to the chosen screen', async () => {
    const fixture = await start({ compact: true });
    const element = fixture.nativeElement as HTMLElement;
    const button = element.querySelector<HTMLButtonElement>('[data-testid="menu-button"]')!;
    expect(button.getAttribute('aria-label')).toBe('Abrir menu de seções');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    button.click();
    fixture.detectChanges();
    expect(button.getAttribute('aria-expanded')).toBe('true');

    const link = Array.from(nav(element)!.querySelectorAll<HTMLAnchorElement>('a'))
      .find(anchor => anchor.getAttribute('href') === '/avisos')!;
    link.click();
    await fixture.whenStable();
    await Promise.resolve();
    fixture.detectChanges();
    expect(TestBed.inject(Router).url).toBe('/avisos');
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement?.id).toBe('main-content');
  });

  it('keeps the menu always visible and without a menu button on a wide screen', async () => {
    const fixture = await start({ compact: false });
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="menu-button"]')).toBeNull();
    expect(nav(element)).not.toBeNull();
  });

  it('announces a new version without reloading by itself, and an unusable copy', async () => {
    const fixture = await start();
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('[data-testid="update-banner"]')).toBeNull();
    versionUpdates.next({ type: 'VERSION_DETECTED', version: { hash: 'b' } });
    fixture.detectChanges();
    expect(element.querySelector('[data-testid="update-banner"]')).toBeNull();
    versionUpdates.next({ type: 'VERSION_READY', currentVersion: { hash: 'a' }, latestVersion: { hash: 'b' } });
    fixture.detectChanges();
    const banner = element.querySelector('[data-testid="update-banner"]')!;
    expect(banner.getAttribute('role')).toBe('status');
    expect(banner.textContent).toContain('Nova versão disponível');
    unrecoverable.next({ type: 'UNRECOVERABLE_STATE', reason: 'hash mismatch' });
    fixture.detectChanges();
    expect(element.querySelector('[data-testid="update-banner"]')!.textContent).toContain('não pode mais ser usada');
  });

  it('works without a service worker (development and browsers without support)', async () => {
    const fixture = await start({ serviceWorker: false });
    expect(fixture.nativeElement.querySelector('[data-testid="update-banner"]')).toBeNull();
    expect(nav(fixture.nativeElement)).not.toBeNull();
  });
});
