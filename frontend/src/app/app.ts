import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, DestroyRef, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';
import { filter, map } from 'rxjs';
import { SessionStateService } from './identity/session-state.service';
import { NAVIGATION, isPublicFlow } from './shell/navigation';

/** Below this width the menu becomes a drawer opened by a button (phones and narrow windows). */
export const COMPACT_LAYOUT = '(max-width: 59.99rem)';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatToolbarModule, MatButtonModule, MatSidenavModule],
  templateUrl: './app.html',
  styleUrl: './app.scss'
})
export class App {
  private readonly router = inject(Router);
  private readonly session = inject(SessionStateService);
  private readonly updates = inject(SwUpdate, { optional: true });
  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');
  private focusMainAfterNavigation = false;

  readonly navigation = NAVIGATION;
  readonly compact = toSignal(
    inject(BreakpointObserver).observe(COMPACT_LAYOUT).pipe(map(state => state.matches)),
    { initialValue: false });
  readonly menuOpen = signal(false);
  readonly url = signal(this.router.url);
  readonly showNavigation = computed(() => !!this.session.user() && !isPublicFlow(this.url()));
  readonly updateReady = signal(false);
  readonly updateBroken = signal(false);

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      takeUntilDestroyed(destroyRef),
    ).subscribe(event => {
      this.url.set(event.urlAfterRedirects);
      // The menu follows the server: a session that ended elsewhere hides it on the next screen.
      this.session.refresh();
      if (this.focusMainAfterNavigation) {
        this.focusMainAfterNavigation = false;
        this.menuOpen.set(false);
        queueMicrotask(() => this.main().nativeElement.focus());
      }
    });
    if (this.updates?.isEnabled) {
      this.updates.versionUpdates.pipe(
        filter(event => event.type === 'VERSION_READY'),
        takeUntilDestroyed(destroyRef),
      ).subscribe(() => this.updateReady.set(true));
      this.updates.unrecoverable.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => this.updateBroken.set(true));
    }
  }

  toggleMenu(): void {
    this.menuOpen.update(open => !open);
  }

  menuChanged(open: boolean): void {
    this.menuOpen.set(open);
  }

  /** A choice in the menu moves focus to the new screen, so keyboard and screen reader users start there. */
  chooseSection(): void {
    this.focusMainAfterNavigation = true;
    if (this.compact()) this.menuOpen.set(false);
  }

  reload(): void {
    // Reloading only fetches the new shell; nothing typed is kept, so the banner never reloads by itself.
    document.location.reload();
  }
}
