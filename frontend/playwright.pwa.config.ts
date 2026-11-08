import { defineConfig, devices } from '@playwright/test';

/**
 * H09.1: PWA checks on the PRODUCTION build (service worker enabled), served by e2e/support/pwa-server.mjs with a
 * test double of the API. Run `npm run build` first. Real installation on the Android phones is validated by hand.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: 'pwa.spec.ts',
  fullyParallel: false,
  forbidOnly: Boolean(process.env['CI']),
  retries: process.env['CI'] ? 1 : 0,
  reporter: [['html', { open: 'never', outputFolder: 'playwright-report-pwa' }], ['list']],
  use: {
    baseURL: 'http://127.0.0.1:4300',
    trace: 'on-first-retry',
  },
  webServer: {
    command: 'node e2e/support/pwa-server.mjs',
    url: 'http://127.0.0.1:4300/healthz',
    reuseExistingServer: !process.env['CI'],
    timeout: 30_000,
  },
  projects: [
    { name: 'android-chrome', use: { ...devices['Pixel 7'] } },
  ],
});
