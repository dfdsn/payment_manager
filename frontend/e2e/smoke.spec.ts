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
