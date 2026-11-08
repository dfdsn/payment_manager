import { BrowserContext, Page, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';

// H09.1 on the production build: installable manifest, service worker limited to the app shell, no API data in
// any cache, menu and quick action on a phone-sized screen. The API answers come from e2e/support/pwa-server.mjs.
const MARKER = 'CONTA-SIGILOSA-H091';

async function signIn(context: BrowserContext) {
  await context.addCookies([{ name: 'SESSION', value: 'e2e-ativa', domain: '127.0.0.1', path: '/', httpOnly: true }]);
}

async function workerReady(page: Page) {
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  // Angular registers when the app is stable; the reload puts the page under the worker's control.
  await page.reload();
  await expect.poll(() => page.evaluate(() => Boolean(navigator.serviceWorker.controller))).toBe(true);
}

async function cachedEntries(page: Page) {
  return page.evaluate(async () => {
    const entries: { cache: string; url: string; body: string }[] = [];
    for (const name of await caches.keys()) {
      const cache = await caches.open(name);
      for (const request of await cache.keys()) {
        const response = await cache.match(request);
        entries.push({ cache: name, url: request.url, body: response ? await response.clone().text() : '' });
      }
    }
    return entries;
  });
}

test('i1 the manifest meets Chrome installability with standalone display, icons and Portuguese shortcuts', async ({ page }) => {
  await page.goto('/entrar');
  await page.evaluate(async () => { await navigator.serviceWorker.ready; });
  const cdp = await page.context().newCDPSession(page);
  const manifest = await cdp.send('Page.getAppManifest');
  expect(manifest.errors).toEqual([]);
  const installability = await cdp.send('Page.getInstallabilityErrors');
  expect(installability.installabilityErrors).toEqual([]);

  const parsed = JSON.parse(manifest.data!);
  expect(parsed).toMatchObject({ lang: 'pt-BR', display: 'standalone', start_url: '/entrar', scope: '/', short_name: 'Contas' });
  expect(parsed.icons.map((icon: { sizes: string; purpose: string }) => `${icon.sizes}:${icon.purpose}`))
    .toEqual(['192x192:any', '512x512:any', '512x512:maskable']);
  expect(parsed.shortcuts[0]).toMatchObject({ name: 'Adicionar despesa', url: '/despesas?nova=1' });
  for (const icon of parsed.icons) {
    const response = await page.request.get(`/${icon.src}`);
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toBe('image/png');
  }
  await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#132f4c');
  await expect(page.locator('html')).toHaveAttribute('lang', 'pt-BR');
});

test('i2 i3 the worker caches only the app shell and never an API answer, even after using private screens', async ({ page, context }) => {
  await signIn(context);
  await page.goto('/entrar');
  await workerReady(page);
  await expect(page.getByRole('heading', { name: 'Olá, Diego' })).toBeVisible();
  await page.getByTestId('menu-button').click();
  await page.getByRole('navigation', { name: 'Seções' }).getByRole('link', { name: /Avisos/ }).click();
  await expect(page.getByText(`Resumo ${MARKER}`)).toBeVisible();
  const direct = await page.evaluate(async () => (await fetch('/api/v1/identity/me')).status);
  expect(direct).toBe(200);

  const entries = await cachedEntries(page);
  expect(entries.length).toBeGreaterThan(0);
  expect(entries.filter(entry => new URL(entry.url).pathname.startsWith('/api/'))).toEqual([]);
  expect(entries.filter(entry => entry.body.includes(MARKER) || entry.body.includes('admin@example.com'))).toEqual([]);
  const shell = readFileSync('ngsw-config.json', 'utf8');
  expect(JSON.parse(shell).dataGroups).toBeUndefined();
  expect(entries.map(entry => new URL(entry.url).pathname)).toContain('/index.html');
});

test('i4 without connection no previous answer is served, so the screen cannot show old data as current', async ({ page, context }) => {
  await signIn(context);
  await page.goto('/avisos');
  await workerReady(page);
  await expect(page.getByText(`Resumo ${MARKER}`)).toBeVisible();

  await context.setOffline(true);
  const offline = await page.evaluate(async () => {
    try {
      const response = await fetch('/api/v1/notifications/inbox?view=ACTIVE&page=0&size=20');
      return `status ${response.status}`;
    } catch {
      return 'network-error';
    }
  });
  // ngsw answers 504 when the network fails for a request it does not cache; either way, no data.
  expect(['network-error', 'status 504']).toContain(offline);
  await context.setOffline(false);
});

test('i5 navigations to /api reach the server instead of the cached app shell', async ({ page, context }) => {
  await page.goto('/entrar');
  await workerReady(page);
  const anonymous = await page.goto('/api/v1/identity/me');
  expect(anonymous!.status()).toBe(401);
  expect(anonymous!.headers()['content-type']).toContain('application/json');
  await signIn(context);
  const signed = await page.goto('/api/v1/identity/me');
  expect(signed!.status()).toBe(200);
  expect(signed!.headers()['cache-control']).toContain('no-store');
});

test('i6 after the session ends the installed app shows the login form and no menu, with nothing kept', async ({ page, context }) => {
  await signIn(context);
  await page.goto('/entrar');
  await workerReady(page);
  await expect(page.getByTestId('quick-add')).toBeVisible();

  await context.clearCookies();
  await page.goto('/entrar');
  await expect(page.getByLabel('Email')).toBeVisible();
  await expect(page.getByTestId('menu-button')).toHaveCount(0);
  await expect(page.getByTestId('quick-add')).toHaveCount(0);
  await expect(page.getByText('Olá, Diego')).toHaveCount(0);
});

test('i7 the phone layout has no horizontal scroll and keeps the quick action reachable', async ({ page, context }) => {
  await signIn(context);
  await page.goto('/entrar');
  await expect(page.getByTestId('quick-add')).toBeVisible();
  for (const path of ['/entrar', '/avisos']) {
    await page.goto(path);
    await expect(page.getByTestId('quick-add')).toBeInViewport();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
  }
});
