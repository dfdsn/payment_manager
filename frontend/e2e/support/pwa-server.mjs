// H09.1 E2E only: serves the production build (dist/frontend/browser) the way frontend/nginx.conf does and answers
// a few /api routes with a TEST DOUBLE of the backend. It is never part of an image or of production.
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../dist/frontend/browser/', import.meta.url));
const port = Number(process.env.PWA_E2E_PORT ?? 4300);
const types = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png',
  '.ico': 'image/x-icon', '.svg': 'image/svg+xml',
};
export const FINANCIAL_MARKER = 'CONTA-SIGILOSA-H091';
const signedIn = request => /(?:^|;\s*)SESSION=e2e-ativa(?:;|$)/.test(request.headers.cookie ?? '');

function json(response, status, body) {
  // Same policy as the real backend (Spring Security default): authenticated answers are never stored.
  response.writeHead(status, {
    'Content-Type': 'application/json', 'Cache-Control': 'no-cache, no-store, max-age=0, must-revalidate',
  });
  response.end(JSON.stringify(body));
}

function api(request, response, path) {
  if (!signedIn(request)) {
    return json(response, 401, { code: 'AUTHENTICATION_REQUIRED', message: 'Entre para continuar.', fieldErrors: [] });
  }
  if (path === '/api/v1/identity/me') {
    return json(response, 200, {
      userId: 'u-admin', displayName: 'Diego', email: 'admin@example.com', spaceId: 's', spaceName: 'Casa E2E',
      role: 'ADMINISTRATOR', currency: 'BRL', locale: 'pt-BR', timeZone: 'America/Sao_Paulo',
    });
  }
  if (path === '/api/v1/notifications/inbox') {
    return json(response, 200, {
      items: [{
        id: 'n1', type: 'REMINDER_SUMMARY', title: `Resumo ${FINANCIAL_MARKER}`, message: '1 conta vence hoje',
        createdAt: '2026-11-08T12:00:00Z', readAt: null, dismissedAt: null, failure: null,
        summary: {
          id: 'r1', date: '2026-11-08', slot: 'SECOND', scheduledTime: '18:00', timeZone: 'America/Sao_Paulo',
          count: 1, total: '1234.56', estimatedCount: 0, estimatedTotal: '0.00', overdueCount: 0, remaining: 0,
          link: '/lembretes/resumos/r1',
        },
      }],
      page: 0, size: 20, totalItems: 1, totalPages: 1, unreadCount: 1, view: 'ACTIVE',
    });
  }
  if (path === '/api/v1/notifications/inbox/unread-count') return json(response, 200, { unreadCount: 1 });
  return json(response, 404, { code: 'NOT_FOUND', message: 'Rota não simulada no E2E da PWA.', fieldErrors: [] });
}

async function file(response, path) {
  const target = normalize(join(root, path));
  if (!target.startsWith(root)) return false;
  try {
    if (!(await stat(target)).isFile()) return false;
  } catch {
    return false;
  }
  const revalidate = ['/ngsw-worker.js', '/ngsw.json', '/safety-worker.js', '/manifest.webmanifest', '/index.html']
    .includes(path);
  const hashed = /^\/(chunk|main|polyfills|styles)-[A-Z0-9]+\.(js|css)$/i.test(path);
  response.writeHead(200, {
    'Content-Type': types[extname(path)] ?? 'application/octet-stream',
    'Cache-Control': revalidate ? 'no-cache' : hashed ? 'public, max-age=31536000, immutable' : 'no-cache',
  });
  response.end(await readFile(target));
  return true;
}

createServer(async (request, response) => {
  const path = decodeURIComponent(new URL(request.url ?? '/', 'http://localhost').pathname);
  if (path.startsWith('/api/')) return api(request, response, path);
  if (path === '/healthz') return response.end('ok\n');
  if (await file(response, path)) return;
  if (/\.[a-z0-9]+$/i.test(path)) {
    response.writeHead(404);
    return response.end();
  }
  await file(response, '/index.html');
}).listen(port, '127.0.0.1', () => console.log(`PWA E2E server on http://127.0.0.1:${port}`));
