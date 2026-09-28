import { expect, test } from '@playwright/test';

test('completes the protected initial setup and closes public registration', async ({ page }) => {
  await page.route('**/api/v1/setup/status', route => route.fulfill({
    status: 200, contentType: 'application/json', body: '{"status":"AVAILABLE"}',
  }));
  await page.route('**/api/v1/setup', async route => {
    expect(route.request().headers()['x-setup-secret']).toBe('segredo-temporario');
    expect(route.request().postDataJSON()).not.toHaveProperty('setupSecret');
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({
      administratorId: 'admin-id', administratorName: 'Diego', email: 'diego@example.com',
      spaceId: 'space-id', spaceName: 'Minha casa', currency: 'BRL', locale: 'pt-BR', timeZone: 'America/Sao_Paulo',
    }) });
  });
  await page.goto('/configuracao-inicial');
  await page.getByLabel('Segredo temporário').fill('segredo-temporario');
  await page.getByLabel('Nome do administrador').fill('Diego');
  await page.getByLabel('Email').fill('diego@example.com');
  await page.getByLabel('Senha').fill('frase segura 2026');
  await page.getByLabel('Nome do espaço').fill('Minha casa');
  await page.getByRole('button', { name: 'Criar administrador e espaço' }).click();
  await expect(page.getByRole('heading', { name: 'Espaço configurado' })).toBeVisible();
  await expect(page.getByText(/O cadastro inicial está fechado/)).toBeVisible();
  await expect(page.locator('form')).toHaveCount(0);
});

test('confirms a variable charge without settling it and keeps the typed value on conflict', async ({ page }) => {
  const estimate = { id: 'bill', origin: 'RECURRENCE', chargeConfirmed: false, description: 'Energia', amount: '180.00',
    currency: 'BRL', status: 'PENDING', dueDate: '2026-10-10', paymentDate: null, paidAmount: null, paidByUserId: null,
    referenceDate: '2026-10-10', overdue: false, categoryName: null, categoryId: null, responsibleUserId: null,
    responsibleDisplayName: null, notes: null, createdByDisplayName: 'Diego', paidByDisplayName: null,
    createdAt: '2026-09-28T12:00:00Z', version: 2, paymentAudit: null, chargeConfirmation: null, history: [] };
  let current: Record<string, unknown> = estimate;
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  await page.route('**/api/v1/identity/me', route => route.fulfill(json({ userId: 'actor', timeZone: 'America/Sao_Paulo' })));
  await page.route('**/api/v1/identity/members', route => route.fulfill(json([{ userId: 'actor', displayName: 'Diego', currentUser: true }])));
  await page.route('**/api/v1/categories**', route => route.fulfill(json([])));
  await page.route('**/api/v1/auth/csrf', route => route.fulfill(json({ headerName: 'X-XSRF-TOKEN' })));
  await page.route('**/api/v1/expenses/filter-options', route => route.fulfill(json({ responsiblePeople: [], payerPeople: [] })));
  await page.route('**/api/v1/expenses?**', route => route.fulfill(json({ content: [current], page: 0, size: 20,
    totalElements: 1, totalPages: 1, sort: 'REFERENCE_DATE', direction: 'ASC' })));
  await page.route('**/api/v1/expenses/bill', route => route.fulfill(json(current)));
  const bodies: unknown[] = [];
  await page.route('**/api/v1/expenses/bill/charge-confirmation', async route => {
    bodies.push(route.request().postDataJSON());
    if (bodies.length === 1) {
      current = { ...estimate, version: 3, dueDate: '2026-10-12' };
      await route.fulfill(json({ code: 'EXPENSE_STATE_CONFLICT', message: 'A despesa mudou.' }, 409));
      return;
    }
    current = { ...estimate, version: 4, amount: '205.40', chargeConfirmed: true, chargeConfirmation: {
      estimatedAmount: '180.00', confirmedAt: '2026-09-28T13:00:00Z', confirmedByUserId: 'actor', confirmedByDisplayName: 'Diego' } };
    await route.fulfill(json(current));
  });

  await page.goto('/despesas');
  await expect(page.getByText(/Valor estimado atual: R\$\s*180,00/)).toBeVisible();
  await page.getByRole('button', { name: 'Confirmar valor da cobrança' }).click();
  await expect(page.getByText(/confirmar não registra pagamento/)).toBeVisible();
  await page.getByLabel('Valor confirmado da cobrança').fill('205,40');
  await page.locator('.charge-confirmation-card').getByRole('button', { name: 'Confirmar valor da cobrança' }).click();
  await expect(page.getByText('A cobrança mudou antes da confirmação.')).toBeVisible();
  await expect(page.getByLabel('Valor confirmado da cobrança')).toHaveValue('205,40');
  await page.getByRole('button', { name: /Revisei: usar versão atual/ }).click();
  await page.locator('.charge-confirmation-card').getByRole('button', { name: 'Confirmar valor da cobrança' }).click();
  await expect(page.getByText(/continua pendente de quitação/)).toBeVisible();
  expect(bodies).toEqual([{ version: 2, confirmedAmount: '205.40' }, { version: 3, confirmedAmount: '205.40' }]);
  await expect(page.getByText(/Valor da cobrança confirmado por Diego/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Quitar despesa' })).toBeVisible();
});
