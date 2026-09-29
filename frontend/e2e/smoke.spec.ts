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

test('changes a recurrence from a period after reviewing the impact and keeps the typed data on conflict', async ({ page }) => {
  const segment = { effectiveMonth: '2026-09-01', description: 'Energia', amount: '180.00', frequency: 'MONTHLY', dueDay: 5,
    categoryId: null, categoryName: null, responsibleUserId: null, responsibleDisplayName: null };
  const recurrence = { id: 'rec-1', description: 'Energia', amount: '180.00', valueType: 'VARIABLE_ESTIMATE', frequency: 'MONTHLY',
    firstDueDate: '2026-09-05', lastDueDate: null, baseDay: 5, categoryId: null, categoryName: null, responsibleUserId: null,
    responsibleDisplayName: null, createdByDisplayName: 'Diego', createdAt: '2026-09-01T12:00:00Z', version: 0,
    previewDates: ['2026-09-05'], upcomingDates: ['2026-09-05', '2026-10-05', '2026-11-05'], closedAt: null, closedByDisplayName: null,
    closureReason: null, segments: [segment], changes: [] };
  const impact = { recurrenceId: 'rec-1', operation: 'CHANGE', version: 0, effectiveDueDate: '2026-10-05', changedFields: ['amount'],
    impactToken: 'token-1', updatedCount: 1, removedCount: 0, reviewCount: 0, preservedCount: 1, forecasts: [],
    occurrences: [
      { expenseId: 'e-oct', scheduledDueDate: '2026-10-05', dueDate: '2026-10-05', description: 'Energia', amount: '195.00',
        status: 'PENDING', chargeConfirmed: true, action: 'PRESERVE', reason: 'UNCHANGED', changes: [], preservedFields: ['amount'] },
      { expenseId: 'e-nov', scheduledDueDate: '2026-11-05', dueDate: '2026-11-05', description: 'Energia', amount: '180.00',
        status: 'PENDING', chargeConfirmed: false, action: 'UPDATE', reason: 'CHANGED',
        changes: [{ field: 'amount', previousValue: '180.00', newValue: '250.00' }], preservedFields: [] }] };
  let current: Record<string, unknown> = recurrence;
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  await page.route('**/api/v1/identity/members', route => route.fulfill(json([{ userId: 'actor', displayName: 'Diego', currentUser: true }])));
  await page.route('**/api/v1/categories**', route => route.fulfill(json([])));
  await page.route('**/api/v1/auth/csrf', route => route.fulfill(json({ headerName: 'X-XSRF-TOKEN' })));
  await page.route('**/api/v1/recurrences', route => route.fulfill(json([current])));
  await page.route('**/api/v1/recurrences/forecasts', route => route.fulfill(json({ from: '2026-09', to: '2027-09', occurrences: [] })));
  const previews: unknown[] = []; const applies: Record<string, unknown>[] = [];
  await page.route('**/api/v1/recurrences/rec-1/changes/preview', async route => {
    previews.push(route.request().postDataJSON());
    await route.fulfill(json({ ...impact, impactToken: `token-${previews.length}` }));
  });
  await page.route('**/api/v1/recurrences/rec-1/changes', async route => {
    applies.push(route.request().postDataJSON());
    if (applies.length === 1) {
      await route.fulfill(json({ code: 'RECURRENCE_IMPACT_CHANGED', message: 'Os lançamentos mudaram desde a prévia. Nada foi aplicado.' }, 409));
      return;
    }
    current = { ...recurrence, version: 1, segments: [segment, { ...segment, effectiveMonth: '2026-10-01', amount: '250.00' }],
      changes: [{ id: 'c-1', type: 'CHANGE', actorUserId: 'actor', actorDisplayName: 'Diego', occurredAt: '2026-09-28T13:00:00Z',
        version: 1, effectiveDueDate: '2026-10-05', changedFields: ['amount'], reason: null, updatedCount: 1, removedCount: 0,
        reviewCount: 0, preservedCount: 1 }] };
    await route.fulfill(json({ recurrence: current, replayed: false, change: (current.changes as unknown[])[0] }));
  });
  const closures: Record<string, unknown>[] = [];
  await page.route('**/api/v1/recurrences/rec-1/closure/preview', route => route.fulfill(json({ ...impact, operation: 'CLOSURE',
    version: 1, changedFields: ['lastDueDate'], impactToken: 'closure-token', occurrences: [{ ...impact.occurrences[1], action: 'REMOVE',
      reason: 'AFTER_END', changes: [] }] })));
  await page.route('**/api/v1/recurrences/rec-1/closure', async route => {
    closures.push(route.request().postDataJSON());
    current = { ...current, version: 2, lastDueDate: '2026-10-05', closedAt: '2026-09-28T14:00:00Z', closedByDisplayName: 'Diego',
      closureReason: 'Mudança' };
    await route.fulfill(json({ recurrence: current, replayed: false, change: { id: 'c-2', type: 'CLOSURE', effectiveDueDate: '2026-10-05' } }));
  });

  await page.goto('/recorrencias');
  await page.getByRole('button', { name: 'Alterar a partir de um vencimento' }).click();
  await expect(page.getByText(/Valores confirmados e vencimentos corrigidos/)).toBeVisible();
  await page.getByLabel('A partir do vencimento').selectOption('2026-10-05');
  await page.getByRole('textbox', { name: 'Estimativa' }).fill('250,00');
  await page.getByRole('button', { name: 'Revisar impacto' }).click();
  await expect(page.getByText('Preservado: estimativa')).toBeVisible();
  await expect(page.getByText('estimativa: 180.00 → 250.00')).toBeVisible();
  await page.getByRole('button', { name: 'Confirmar alteração' }).click();
  await expect(page.getByText(/Nada foi aplicado. Os dados digitados foram mantidos/)).toBeVisible();
  await expect(page.getByRole('textbox', { name: 'Estimativa' })).toHaveValue('250,00');
  await page.getByRole('button', { name: 'Revisar impacto' }).click();
  await page.getByRole('button', { name: 'Confirmar alteração' }).click();
  await expect(page.getByText(/alterada a partir de 2026-10-05/)).toBeVisible();
  expect(applies.map(body => [body['impactToken'], body['amount'], body['version']])).toEqual([['token-1', '250.00', 0], ['token-2', '250.00', 0]]);

  await page.getByRole('button', { name: 'Encerrar recorrência' }).click();
  await expect(page.getByText(/Não apaga o histórico/)).toBeVisible();
  await page.getByRole('combobox', { name: 'Último vencimento' }).selectOption('2026-10-05');
  await page.getByLabel('Motivo do encerramento').fill('Mudança');
  await page.getByRole('button', { name: 'Revisar impacto' }).click();
  await expect(page.getByText('Sai da programação (cancelado com motivo)')).toBeVisible();
  await page.getByRole('button', { name: 'Confirmar encerramento' }).click();
  await expect(page.getByText(/Encerrada: último vencimento 2026-10-05/)).toBeVisible();
  expect(closures).toEqual([{ version: 1, lastDueDate: '2026-10-05', reason: 'Mudança', impactToken: 'closure-token' }]);
});

test('reviews an installment purchase with the cent remainder on the last installment before creating it', async ({ page }) => {
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  const installments = [['33.33', '2027-01-31'], ['33.33', '2027-02-28'], ['33.34', '2027-03-31']]
    .map(([amount, dueDate], index) => ({ number: index + 1, count: 3, amount, dueDate, expenseId: null, status: null }));
  const preview = { description: 'Sofá', totalAmount: '100.00', installmentCount: 3, firstDueDate: '2027-01-31',
    lastDueDate: '2027-03-31', regularAmount: '33.33', lastAmount: '33.34', lastInstallmentAdjustment: '0.01',
    installmentsSum: '100.00', installments };
  await page.route('**/api/v1/identity/members', route => route.fulfill(json([{ userId: 'actor', displayName: 'Diego', currentUser: true }])));
  await page.route('**/api/v1/categories**', route => route.fulfill(json([{ id: 'cat-1', name: 'Casa', active: true }])));
  await page.route('**/api/v1/auth/csrf', route => route.fulfill(json({ headerName: 'X-XSRF-TOKEN' })));
  await page.route('**/api/v1/identity/me', route => route.fulfill(json({ userId: 'actor', timeZone: 'America/Sao_Paulo' })));
  await page.route('**/api/v1/installment-purchases?**', route => route.fulfill(json({ items: [], page: 0, size: 10, totalItems: 0 })));
  const previews: unknown[] = [];
  await page.route('**/api/v1/installment-purchases/preview', async route => {
    previews.push(route.request().postDataJSON());
    await route.fulfill(json(preview));
  });
  const creations: { body: unknown; key: string | undefined }[] = [];
  await page.route('**/api/v1/installment-purchases', async route => {
    creations.push({ body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] });
    if (creations.length === 1) { await route.abort('failed'); return; }
    await route.fulfill(json({ ...preview, id: 'purchase-1', categoryId: 'cat-1', categoryName: 'Casa',
      responsibleUserId: null, responsibleDisplayName: null, createdByUserId: 'actor', createdByDisplayName: 'Diego',
      createdAt: '2026-09-28T12:00:00Z', installments: installments.map(i => ({ ...i, expenseId: `e-${i.number}`, status: 'PENDING' })) }, 201));
  });

  await page.goto('/compras-parceladas');
  await page.getByLabel('Descrição').fill('Sofá');
  await page.getByLabel('Valor total').fill('100,00');
  await page.getByLabel('Quantidade de parcelas').fill('3');
  await page.getByLabel('Vencimento da primeira parcela').fill('2027-01-31');
  await page.getByLabel('Categoria (opcional)').selectOption('cat-1');
  await page.getByRole('button', { name: 'Revisar parcelas' }).click();
  await expect(page.getByText('Revise antes de confirmar')).toBeVisible();
  await expect(page.getByRole('row', { name: /3\/3 2027-03-31 R\$ 33\.34/ })).toBeVisible();
  await expect(page.getByText(/A última parcela tem R\$ 0\.01 a mais/)).toBeVisible();
  expect(previews).toEqual([{ description: 'Sofá', totalAmount: '100.00', installmentCount: 3, firstDueDate: '2027-01-31',
    categoryId: 'cat-1', responsibleUserId: null }]);

  const confirm = page.getByRole('button', { name: 'Confirmar e criar 3 parcelas' });
  await confirm.click();
  await expect(page.getByText(/a repetição não duplica parcelas/)).toBeVisible();
  await confirm.click();
  await expect(page.getByText(/criada com 3 parcelas pendentes, de 2027-01-31 a 2027-03-31/)).toBeVisible();
  expect(creations).toHaveLength(2);
  expect(creations[1].key).toBe(creations[0].key);
  expect(creations[1].body).toEqual(previews[0]);
});

test('shows purchase progress and pays only the selected pending installments in one batch', async ({ page }) => {
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  const item = (number: number, status: string, extra: Record<string, unknown> = {}) => ({ number, count: 3,
    amount: number === 3 ? '33.34' : '33.33', dueDate: `2026-${String(8 + number).padStart(2, '0')}-15`,
    expenseId: `e-${number}`, status, version: number, overdue: false, description: 'Sofá', categoryName: null,
    responsibleDisplayName: null, paymentDate: null, paidAmount: null, ...extra });
  const progress = (paid: number) => ({ installmentCount: 3, paidCount: paid, pendingCount: 3 - paid,
    overdueCount: paid === 0 ? 1 : 0, cancelledCount: 0, paidAmount: paid ? '66.66' : '0.00',
    pendingAmount: paid ? '33.34' : '100.00', overdueAmount: paid ? '0.00' : '33.33', cancelledAmount: '0.00',
    nextDueDate: paid ? '2026-11-15' : '2026-09-15' });
  let paid = 0;
  const summary = () => ({ id: 'p-1', description: 'Sofá', totalAmount: '100.00', installmentCount: 3,
    firstDueDate: '2026-09-15', lastDueDate: '2026-11-15', categoryName: null, responsibleDisplayName: null,
    createdAt: '2026-08-01T12:00:00Z', progress: progress(paid) });
  const detail = () => ({ ...summary(), categoryId: null, responsibleUserId: null, createdByUserId: 'actor',
    createdByDisplayName: 'Diego', installmentsSum: '100.00', installments: paid
      ? [item(1, 'PAID', { paymentDate: '2026-09-28', paidAmount: '33.33' }), item(2, 'PAID', { paymentDate: '2026-09-28', paidAmount: '33.33' }), item(3, 'PENDING')]
      : [item(1, 'PENDING', { overdue: true }), item(2, 'PENDING'), item(3, 'PENDING')] });
  await page.route('**/api/v1/identity/me', route => route.fulfill(json({ userId: 'actor', timeZone: 'America/Sao_Paulo' })));
  await page.route('**/api/v1/identity/members', route => route.fulfill(json([{ userId: 'actor', displayName: 'Diego', currentUser: true }])));
  await page.route('**/api/v1/categories**', route => route.fulfill(json([])));
  await page.route('**/api/v1/auth/csrf', route => route.fulfill(json({ headerName: 'X-XSRF-TOKEN' })));
  await page.route('**/api/v1/installment-purchases?**', route => route.fulfill(json({ items: [summary()], page: 0, size: 10, totalItems: 1 })));
  await page.route('**/api/v1/installment-purchases/p-1', route => route.fulfill(json(detail())));
  const batches: { body: { items: unknown[] }; key: string | undefined }[] = [];
  await page.route('**/api/v1/expenses/batch-payment', async route => {
    batches.push({ body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] });
    paid = 2;
    await route.fulfill(json({ operationId: 'op-1', replayed: false, items: batches[0].body.items }));
  });

  await page.goto('/compras-parceladas');
  await expect(page.getByText('0 de 3 pagas · 3 pendentes (1 atrasadas)')).toBeVisible();
  await expect(page.getByText(/não é saldo bancário/)).toBeVisible();
  await page.getByRole('button', { name: 'Ver parcelas de Sofá' }).click();
  await expect(page.getByRole('row', { name: /1\/3 2026-09-15 R\$ 33\.33 Atrasada/ })).toBeVisible();
  await page.getByLabel('Selecionar parcela 1/3').check();
  await page.getByLabel('Selecionar parcela 2/3').check();
  await page.getByRole('button', { name: 'Quitar selecionadas (2)' }).click();
  await expect(page.getByText('Confirmo a quitação integral das 2 parcelas, no total de R$ 66.66.')).toBeVisible();
  await page.getByRole('button', { name: 'Confirmar quitação' }).click();
  await expect(page.getByText('Confirme a quitação para continuar.')).toBeVisible();
  expect(batches).toHaveLength(0);
  await page.getByLabel(/Confirmo a quitação integral/).check();
  await page.getByRole('button', { name: 'Confirmar quitação' }).click();
  await expect(page.getByText('2 parcelas quitadas de uma vez.')).toBeVisible();
  await expect(page.getByText('2 de 3 pagas · 1 pendentes')).toBeVisible();
  await expect(page.getByRole('row', { name: /2\/3 2026-10-15 R\$ 33\.33 Paga em 2026-09-28/ })).toBeVisible();
  expect(batches).toHaveLength(1);
  expect(batches[0].key).toBeTruthy();
  expect(batches[0].body).toMatchObject({ items: [{ expenseId: 'e-1', version: 1 }, { expenseId: 'e-2', version: 2 }],
    paidByUserId: 'actor', confirmed: true });
});

test('reviews the impact before changing following installments and cancels one with a replacement purchase', async ({ page }) => {
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  const item = (number: number, status: string, dueDate: string, extra: Record<string, unknown> = {}) => ({ number,
    count: 3, amount: number === 3 ? '33.34' : '33.33', dueDate, expenseId: `e-${number}`, status, version: number,
    overdue: false, description: 'Sofá', categoryId: null, categoryName: null, responsibleUserId: null,
    responsibleDisplayName: null, paymentDate: status === 'PAID' ? '2026-09-28' : null,
    paidAmount: status === 'PAID' ? '33.33' : null, ...extra });
  let installments = [item(1, 'PAID', '2026-09-15'), item(2, 'PENDING', '2026-10-15'), item(3, 'PENDING', '2026-11-15')];
  const detail = () => ({ id: 'p-1', description: 'Sofá', totalAmount: '100.00', installmentCount: 3,
    firstDueDate: '2026-09-15', lastDueDate: '2026-11-15', categoryId: null, categoryName: null, responsibleUserId: null,
    responsibleDisplayName: null, createdByUserId: 'actor', createdByDisplayName: 'Diego', createdAt: '2026-08-01T12:00:00Z',
    installmentsSum: '100.00', replacesPurchaseId: null, installments,
    progress: { installmentCount: 3, paidCount: 1, pendingCount: 2, overdueCount: 0, cancelledCount: 0, paidAmount: '33.33',
      pendingAmount: '66.67', overdueAmount: '0.00', cancelledAmount: '0.00', nextDueDate: '2026-10-15' } });
  await page.route('**/api/v1/identity/me', route => route.fulfill(json({ userId: 'actor', timeZone: 'America/Sao_Paulo' })));
  await page.route('**/api/v1/identity/members', route => route.fulfill(json([{ userId: 'actor', displayName: 'Diego', currentUser: true }])));
  await page.route('**/api/v1/categories**', route => route.fulfill(json([])));
  await page.route('**/api/v1/auth/csrf', route => route.fulfill(json({ headerName: 'X-XSRF-TOKEN' })));
  await page.route('**/api/v1/installment-purchases?**', route => route.fulfill(json({ items: [detail()], page: 0, size: 10, totalItems: 1 })));
  await page.route('**/api/v1/installment-purchases/p-1', route => route.fulfill(json(detail())));
  const calls: { url: string; body: Record<string, unknown>; key: string | undefined }[] = [];
  const record = (route: import('@playwright/test').Route) => calls.push({ url: new URL(route.request().url()).pathname,
    body: route.request().postDataJSON(), key: route.request().headers()['idempotency-key'] });
  await page.route('**/api/v1/installment-purchases/p-1/changes/preview', route => { record(route); return route.fulfill(json({
    changeType: 'CHANGE', impactToken: 'tok-change', affectedAmount: '66.67', replacement: null,
    affected: [{ number: 2, expenseId: 'e-2', version: 2, amount: '33.33', dueDate: '2026-10-15',
      changes: [{ field: 'dueDate', from: '2026-10-15', to: '2026-10-20' }] },
    { number: 3, expenseId: 'e-3', version: 3, amount: '33.34', dueDate: '2026-11-15',
      changes: [{ field: 'dueDate', from: '2026-11-15', to: '2026-11-20' }] }],
    preserved: [{ number: 1, status: 'PAID', reason: 'PAID' }] })); });
  await page.route('**/api/v1/installment-purchases/p-1/changes', route => {
    record(route);
    installments = [installments[0], item(2, 'PENDING', '2026-10-20', { version: 3 }), item(3, 'PENDING', '2026-11-20', { version: 4 })];
    return route.fulfill(json({ changeId: 'c-1', changeType: 'CHANGE', affectedCount: 2, preservedCount: 1,
      purchase: detail(), replacement: null, replayed: false }));
  });
  const replacement = { description: 'Sofá (restante)', totalAmount: '40.00', installmentCount: 2, firstDueDate: '2026-11-20',
    lastDueDate: '2026-12-20', installmentsSum: '40.00', lastInstallmentAdjustment: '0.00', installments: [] };
  await page.route('**/api/v1/installment-purchases/p-1/cancellation/preview', route => { record(route); return route.fulfill(json({
    changeType: 'CANCELLATION', impactToken: 'tok-cancel', affectedAmount: '33.34', replacement,
    affected: [{ number: 3, expenseId: 'e-3', version: 4, amount: '33.34', dueDate: '2026-11-20',
      changes: [{ field: 'status', from: 'PENDING', to: 'CANCELLED' }] }],
    preserved: [{ number: 1, status: 'PAID', reason: 'PAID' }, { number: 2, status: 'PENDING', reason: 'NOT_SELECTED' }] })); });
  await page.route('**/api/v1/installment-purchases/p-1/cancellation', route => {
    record(route);
    installments = [installments[0], installments[1], item(3, 'CANCELLED', '2026-11-20', { version: 5 })];
    return route.fulfill(json({ changeId: 'c-2', changeType: 'CANCELLATION', affectedCount: 1, preservedCount: 2,
      purchase: detail(), replacement: { ...detail(), id: 'p-2', description: 'Sofá (restante)', installmentCount: 2 },
      replayed: false }));
  });

  await page.goto('/compras-parceladas');
  await page.getByRole('button', { name: 'Ver parcelas de Sofá' }).click();
  await page.getByRole('button', { name: 'Alterar parcelas pendentes' }).click();
  await page.getByLabel('A partir da parcela').selectOption({ label: '2/3 · 2026-10-15' });
  await page.getByLabel('Esta e as próximas pendentes').check();
  await page.getByLabel('Alterar vencimento').check();
  await page.getByLabel('Novo vencimento').fill('2026-10-20');
  await page.getByRole('button', { name: 'Revisar impacto' }).click();
  await expect(page.getByText('2 parcelas serão alteradas.')).toBeVisible();
  await expect(page.getByText('Vencimento: 2026-11-15 → 2026-11-20')).toBeVisible();
  await expect(page.getByText('1 (paga, não muda)')).toBeVisible();
  expect(calls.filter(c => c.url.endsWith('/changes'))).toHaveLength(0);
  await page.getByRole('button', { name: 'Confirmar alteração' }).click();
  await expect(page.getByText('Alteração aplicada a 2 parcelas; 1 preservada.')).toBeVisible();
  await expect(page.getByRole('row', { name: /3\/3 2026-11-20 R\$ 33\.34/ })).toBeVisible();
  const change = calls.find(c => c.url.endsWith('/changes'))!;
  expect(change.key).toBeTruthy();
  expect(change.body).toMatchObject({ fromNumber: 2, scope: 'THIS_AND_FOLLOWING', changedFields: ['dueDate'],
    dueDate: '2026-10-20', impactToken: 'tok-change' });

  await page.getByLabel('Selecionar parcela 3/3').check();
  await page.getByRole('button', { name: 'Cancelar selecionadas (1)' }).click();
  await page.getByLabel('Motivo do cancelamento').fill('Loja reduziu o saldo');
  await page.getByLabel(/Criar nova compra com o restante/).check();
  await page.getByLabel('Valor total da nova compra').fill('40.00');
  await page.getByRole('button', { name: 'Revisar cancelamento' }).click();
  await expect(page.getByText('1 parcela será cancelada, somando R$ 33.34.')).toBeVisible();
  await expect(page.getByText(/Nova compra “Sofá \(restante\)”: R\$ 40\.00 em 2 parcelas/)).toBeVisible();
  await page.getByRole('button', { name: 'Confirmar cancelamento' }).click();
  await expect(page.getByText('1 parcela cancelada; 2 preservadas. Nova compra “Sofá (restante)” criada com 2 parcelas.')).toBeVisible();
  const cancel = calls.find(c => c.url.endsWith('/cancellation'))!;
  expect(cancel.key).toBeTruthy();
  expect(cancel.key).not.toBe(change.key);
  expect(cancel.body).toMatchObject({ installmentNumbers: [3], reason: 'Loja reduziu o saldo', impactToken: 'tok-cancel',
    replacement: { description: 'Sofá (restante)', totalAmount: '40.00', installmentCount: 2, firstDueDate: '2026-11-20' } });
});

test('H06.1 shows the due-date dashboard with filters applied to totals and list', async ({ page }) => {
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  const indicators = (planned: string) => ({ plannedCount: 3, plannedTotal: planned, plannedEstimated: '180.00', paidCount: 1,
    paidTotal: '155.00', pendingCount: 2, pendingTotal: '1680.00', pendingEstimated: '180.00', overdueCount: 1,
    overdueTotal: '1500.00', overdueEstimated: '0.00', adjustmentIncrease: '5.00', adjustmentDiscount: '0.00', adjustmentNet: '5.00' });
  const reportCalls: URL[] = [];
  const listCalls: URL[] = [];
  await page.route('**/api/v1/identity/me', route => route.fulfill(json({ userId: 'actor', timeZone: 'America/Sao_Paulo' })));
  await page.route('**/api/v1/categories**', route => route.fulfill(json([{ id: 'cat', name: 'Moradia', archived: false, version: 0, updatedAt: '' }])));
  await page.route('**/api/v1/expenses/filter-options', route => route.fulfill(json({ responsiblePeople: [], payerPeople: [] })));
  await page.route('**/api/v1/reports/due-dashboard?**', route => {
    const url = new URL(route.request().url());
    reportCalls.push(url);
    const month = url.searchParams.get('month')!;
    route.fulfill(json({ month, periodStart: `${month}-01`, periodEnd: `${month}-30`, dateBasis: 'DUE_DATE', today: '2026-09-29',
      timeZone: 'America/Sao_Paulo', indicators: indicators(url.searchParams.get('categoryId') ? '1500.00' : '1835.00'),
      previousPending: { dueBefore: `${month}-01`, count: 1, total: '800.00', estimated: '0.00', overdueCount: 1, overdueTotal: '800.00' } }));
  });
  await page.route('**/api/v1/expenses?**', route => {
    listCalls.push(new URL(route.request().url()));
    route.fulfill(json({ content: [{ id: 'rent', origin: 'ONE_OFF', installment: null, chargeConfirmed: true, description: 'Aluguel',
      amount: '1500.00', currency: 'BRL', status: 'PENDING', dueDate: '2026-09-10', paymentDate: null, paidAmount: null,
      paidByUserId: null, referenceDate: '2026-09-10', overdue: true, categoryName: 'Moradia', categoryId: 'cat',
      responsibleUserId: null, responsibleDisplayName: null, notes: null, createdByDisplayName: 'Diego', paidByDisplayName: null,
      createdAt: '2026-09-01T12:00:00Z', version: 0, history: [] }], page: 0, size: 20, totalElements: 1, totalPages: 1,
      sort: 'REFERENCE_DATE', direction: 'ASC' }));
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/painel');
  await expect(page.getByRole('heading', { name: 'Painel por vencimento' })).toBeVisible();
  await expect(page.getByTestId('planned-total')).toHaveText(/R\$\s*1\.835,00/);
  await expect(page.getByTestId('pending-total')).toHaveText(/R\$\s*1\.680,00/);
  await expect(page.getByTestId('adjustment-net')).toHaveText(/\+R\$\s*5,00/);
  await expect(page.getByText('Base temporal: vencimento')).toBeVisible();
  await expect(page.getByTestId('previous-total')).toContainText('800,00');
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  const firstMonth = reportCalls[0].searchParams.get('month')!;
  expect(listCalls[0].searchParams.get('dateBasis')).toBe('DUE_DATE');
  expect(listCalls[0].searchParams.get('dateFrom')).toBe(`${firstMonth}-01`);

  await page.getByLabel('Categoria').selectOption('cat');
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  await expect(page.getByTestId('planned-total')).toHaveText(/R\$\s*1\.500,00/);
  expect(reportCalls.at(-1)!.searchParams.get('categoryId')).toBe('cat');
  expect(listCalls.at(-1)!.searchParams.get('categoryId')).toBe('cat');

  await page.getByRole('button', { name: 'Próximo mês' }).click();
  await expect.poll(() => reportCalls.at(-1)!.searchParams.get('month')).not.toBe(firstMonth);
  expect(listCalls.at(-1)!.searchParams.get('categoryId')).toBe('cat');
});

test('H06.2 shows active payments by payment date with payer, recorder and correction author', async ({ page }) => {
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  const calls: URL[] = [];
  await page.route('**/api/v1/identity/me', route => route.fulfill(json({ userId: 'actor', timeZone: 'America/Sao_Paulo' })));
  await page.route('**/api/v1/categories**', route => route.fulfill(json([])));
  await page.route('**/api/v1/expenses/filter-options', route => route.fulfill(json({ responsiblePeople: [],
    payerPeople: [{ userId: 'bia', displayName: 'Bia', activeMember: true }] })));
  await page.route('**/api/v1/reports/payments?**', route => {
    const url = new URL(route.request().url());
    calls.push(url);
    const month = url.searchParams.get('month')!;
    const filtered = url.searchParams.get('payerUserId') === 'bia';
    route.fulfill(json({ month, periodStart: `${month}-01`, periodEnd: `${month}-31`, dateBasis: 'PAYMENT_DATE',
      timeZone: 'America/Sao_Paulo',
      indicators: { count: filtered ? 1 : 2, paidTotal: filtered ? '99.00' : '254.00', chargeTotal: filtered ? '99.00' : '249.00',
        adjustmentIncrease: filtered ? '0.00' : '5.00', adjustmentDiscount: '0.00', adjustmentNet: filtered ? '0.00' : '5.00' },
      content: [{ expenseId: 'gym', description: 'Academia', origin: 'ONE_OFF', installment: null, dueDate: '2026-10-01',
        chargeAmount: '99.00', chargeConfirmed: true, paidAmount: '99.00', adjustment: '0.00', paymentDate: '2026-10-01',
        payerUserId: 'bia', payerDisplayName: 'Bia', recordedByUserId: 'ana', recordedByDisplayName: 'Ana',
        recordedAt: '2026-09-30T15:00:00Z', batchPayment: false, categoryName: null, responsibleDisplayName: null,
        correctionCount: 1, lastCorrection: { actorUserId: 'ana', actorDisplayName: 'Ana', correctedAt: '2026-10-01T15:00:00Z',
          changedFields: ['paymentDate', 'paidByUserId'] } }],
      page: 0, size: 20, totalElements: filtered ? 1 : 2, totalPages: 1, sort: url.searchParams.get('sort'),
      direction: url.searchParams.get('direction') }));
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/pagamentos');
  await expect(page.getByRole('heading', { name: 'Pagamentos do mês' })).toBeVisible();
  await expect(page.getByText('Base temporal: data do pagamento')).toBeVisible();
  await expect(page.getByTestId('payments-paid-total')).toHaveText(/R\$\s*254,00/);
  await expect(page.getByTestId('payments-adjustment-net')).toHaveText(/\+R\$\s*5,00/);
  await expect(page.getByText('Pago por Bia')).toBeVisible();
  await expect(page.getByTestId('payment-correction')).toContainText('Corrigida por Ana');
  await expect(page.getByTestId('payment-correction')).toContainText('data do pagamento, pagador');
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  expect(calls[0].searchParams.get('sort')).toBe('PAYMENT_DATE');
  expect(calls[0].searchParams.has('status')).toBe(false);

  await page.getByLabel('Pagador').selectOption({ label: 'Bia' });
  await page.getByLabel('Ordenar por').selectOption('PAID_AMOUNT');
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  await expect(page.getByTestId('payments-paid-total')).toHaveText(/R\$\s*99,00/);
  const last = calls[calls.length - 1];
  expect(last.searchParams.get('payerUserId')).toBe('bia');
  expect(last.searchParams.get('sort')).toBe('PAID_AMOUNT');
  expect(last.searchParams.get('page')).toBe('0');
});

test('H06.3 shows the integrated planning with forecasts marked, month drill-down and filters on the whole set', async ({ page }) => {
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  const calls: URL[] = [];
  const totals = (planned: string, forecast: string, estimated: string) => ({ count: 3, plannedTotal: planned,
    confirmedTotal: '1200.00', estimatedTotal: estimated, materializedCount: 2, materializedTotal: '1300.00', forecastCount: 1,
    forecastTotal: forecast, paidCount: 0, paidTotal: '0.00', openCount: 3, openTotal: planned, oneOffTotal: '1200.00',
    installmentTotal: '0.00', recurrenceTotal: '280.00' });
  const months = ['2026-09', '2026-10', '2026-11', '2026-12', '2027-01', '2027-02', '2027-03', '2027-04', '2027-05',
    '2027-06', '2027-07', '2027-08', '2027-09'];
  await page.route('**/api/v1/identity/me', route => route.fulfill(json({ userId: 'actor', timeZone: 'America/Sao_Paulo' })));
  await page.route('**/api/v1/categories**', route => route.fulfill(json([{ id: 'casa', name: 'Casa', archived: false }])));
  await page.route('**/api/v1/expenses/filter-options', route => route.fulfill(json({ responsiblePeople: [], payerPeople: [] })));
  await page.route('**/api/v1/reports/planning**', route => {
    const url = new URL(route.request().url());
    calls.push(url);
    const month = url.searchParams.get('month') ?? '2026-09';
    const filtered = url.searchParams.get('categoryId') === 'casa';
    const monthTotals = month === '2026-09' ? totals(filtered ? '1300.00' : '1480.00', filtered ? '100.00' : '180.00', '180.00')
      : totals('0.00', '0.00', '0.00');
    route.fulfill(json({ horizonStart: '2026-09', horizonEnd: '2027-09', periodStart: '2026-09-01', periodEnd: '2027-09-30',
      dateBasis: 'DUE_DATE', today: '2026-09-29', timeZone: 'America/Sao_Paulo',
      totals: totals(filtered ? '1300.00' : '1480.00', filtered ? '100.00' : '180.00', '180.00'),
      months: months.map(value => ({ month: value, totals: value === '2026-09' ? monthTotals : totals('0.00', '0.00', '0.00') })),
      month, monthTotals,
      content: month !== '2026-09' ? [] : [
        { kind: 'EXPENSE', expenseId: 'car', recurrenceId: null, origin: 'ONE_OFF', installment: null,
          description: 'Seguro do carro com descrição longa para testar a quebra em telas estreitas', date: '2026-09-10',
          dueDate: '2026-09-10', amount: '1200.00', estimated: false, status: 'PENDING', overdue: true, paidAmount: null,
          paymentDate: null, categoryName: 'Casa', responsibleDisplayName: null },
        { kind: 'FORECAST', expenseId: null, recurrenceId: 'luz', origin: 'RECURRENCE', installment: null,
          description: 'Luz', date: '2026-09-20', dueDate: '2026-09-20', amount: '180.00', estimated: true, status: 'FORECAST',
          overdue: false, paidAmount: null, paymentDate: null, categoryName: null, responsibleDisplayName: null },
      ],
      page: 0, size: 20, totalElements: month === '2026-09' ? 2 : 0, totalPages: month === '2026-09' ? 1 : 0 }));
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/planejamento');
  await expect(page.getByRole('heading', { name: 'Planejamento dos próximos meses' })).toBeVisible();
  await expect(page.getByText('Base temporal: vencimento')).toBeVisible();
  await expect(page.getByText(/Não mostra receitas, saldo nem orçamento/)).toBeVisible();
  await expect(page.getByTestId('planning-total')).toHaveText(/R\$\s*1\.480,00/);
  await expect(page.getByTestId('planning-forecast')).toContainText('(1)');
  await expect(page.getByTestId('planning-item')).toHaveCount(2);
  await expect(page.getByTestId('planning-item').nth(1)).toContainText('Previsão');
  await expect(page.getByTestId('planning-item').nth(1)).toContainText('a confirmar');
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  expect(calls[0].searchParams.has('month')).toBe(false);

  await page.getByLabel('Categoria').selectOption({ label: 'Casa' });
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  await expect(page.getByTestId('planning-total')).toHaveText(/R\$\s*1\.300,00/);
  expect(calls[calls.length - 1].searchParams.get('categoryId')).toBe('casa');

  await page.getByRole('button', { name: 'Ver novembro de 2026' }).click();
  await expect(page.getByText('Nenhuma despesa nem previsão neste mês com os filtros escolhidos.')).toBeVisible();
  const last = calls[calls.length - 1];
  expect(last.searchParams.get('month')).toBe('2026-11');
  expect(last.searchParams.get('categoryId')).toBe('casa');
  expect(last.searchParams.get('page')).toBe('0');
});

test('H06.4 downloads the CSV of the selection on screen and explains a refused export', async ({ page }) => {
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  const exportCalls: URL[] = [];
  await page.route('**/api/v1/identity/me', route => route.fulfill(json({ userId: 'actor', timeZone: 'America/Sao_Paulo' })));
  await page.route('**/api/v1/identity/members', route => route.fulfill(json([{ userId: 'actor', displayName: 'Diego', currentUser: true }])));
  await page.route('**/api/v1/categories**', route => route.fulfill(json([])));
  await page.route('**/api/v1/expenses/filter-options', route => route.fulfill(json({ responsiblePeople: [], payerPeople: [] })));
  await page.route('**/api/v1/expenses?**', route => route.fulfill(json({ content: [], page: 0, size: 20, totalElements: 0,
    totalPages: 0, sort: 'REFERENCE_DATE', direction: 'ASC' })));
  const csv = '﻿"Descrição";"Categoria"\r\n"Água; luz";"Casa"\r\n';
  await page.route('**/api/v1/reports/expenses/export**', route => {
    const url = new URL(route.request().url());
    exportCalls.push(url);
    if (url.searchParams.get('status') === 'ALL') {
      route.fulfill(json({ code: 'EXPORT_LIMIT_EXCEEDED', message: 'A seleção tem 10.001 registros e a exportação aceita até '
        + '10.000. Reduza o período ou aplique filtros e exporte em partes.', fieldErrors: [], operationId: 'op' }, 422));
      return;
    }
    route.fulfill({ status: 200, body: csv, headers: { 'Content-Type': 'text/csv;charset=UTF-8', 'X-Export-Rows': '1',
      'Content-Disposition': 'attachment; filename="despesas_vencimento_2026-09-01_a_2026-09-30.csv"' } });
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/despesas');
  await page.getByLabel('Buscar na descrição').fill('água');
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Exportar CSV' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe('despesas_vencimento_2026-09-01_a_2026-09-30.csv');
  const saved = await download.path();
  const bytes = (await import('node:fs')).readFileSync(saved!);
  expect(bytes.subarray(0, 3)).toEqual(Buffer.from([0xef, 0xbb, 0xbf]));
  expect(bytes.toString('utf8')).toBe(csv);
  await expect(page.getByTestId('csv-export-message')).toHaveText(
    'Arquivo despesas_vencimento_2026-09-01_a_2026-09-30.csv baixado com 1 registro.');
  expect(exportCalls[0].searchParams.get('search')).toBe('água');
  expect(exportCalls[0].searchParams.get('sort')).toBe('REFERENCE_DATE');
  expect(exportCalls[0].searchParams.has('page')).toBe(false);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);

  await page.locator('select[formcontrolname="status"]').last().selectOption('ALL');
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  await page.getByRole('button', { name: 'Exportar CSV' }).click();
  await expect(page.getByTestId('csv-export-error')).toContainText('A seleção tem 10.001 registros');
  await expect(page.getByTestId('csv-export-message')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Exportar CSV' })).toBeEnabled();
});
