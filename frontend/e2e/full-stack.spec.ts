import { readFileSync } from 'node:fs';
import { expect, request as playwrightRequest, test } from '@playwright/test';

const email = 'admin@example.com';
const initialPassword = 'frase segura 2026';
const newPassword = 'nova senha segura 2026';
const guestEmail = 'guest@example.com';
const guestPassword = 'senha convidada 2026';

test('runs setup, email confirmation, login, reset and session revocation against real services', async ({ browser, page }) => {
  test.setTimeout(110_000);
  await page.goto('/configuracao-inicial');
  await page.getByLabel('Segredo temporário').fill('local-only-setup-secret-change-me');
  await page.getByLabel('Nome do administrador').fill('Diego');
  await page.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
  await page.getByLabel('Senha').fill(initialPassword);
  await page.getByLabel('Nome do espaço').fill('Minha casa');
  await page.getByRole('button', { name: 'Criar administrador e espaço' }).click();
  await expect(page.getByRole('heading', { name: 'Espaço configurado' })).toBeVisible();

  await page.goto('/confirmar-email');
  await page.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
  await page.getByRole('button', { name: 'Enviar confirmação' }).click();
  await expect(page.getByText(/Se a conta estiver disponível/)).toBeVisible();
  const confirmationLink = await mailLink('confirmar-email');

  await page.goto(confirmationLink);
  await page.getByRole('button', { name: 'Confirmar meu email' }).click();
  await expect(page.getByText('Email confirmado. Agora você já pode entrar.')).toBeVisible();

  await page.goto('/entrar');
  await page.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
  await page.getByLabel('Senha').fill('senha incorreta');
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await expect(page.getByText('Email ou senha inválidos.')).toBeVisible();
  await page.getByLabel('Senha').fill(initialPassword);
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await expect(page.getByText(/Você entrou em/)).toContainText('Minha casa');

  await page.goto('/categorias');
  for (const initial of ['Moradia', 'Alimentação', 'Transporte', 'Saúde', 'Educação', 'Lazer', 'Outros']) {
    await expect(page.getByText(initial, { exact: true })).toBeVisible();
  }
  await page.getByLabel('Nova categoria').fill('Pets');
  await page.getByRole('button', { name: 'Criar categoria' }).click();
  await expect(page.getByText('Categoria criada.')).toBeVisible();

  await page.goto('/despesas');
  await page.getByRole('textbox', { name: 'Descrição', exact: true }).fill('Energia');
  await page.getByRole('textbox', { name: 'Valor', exact: true }).fill('150,25');
  await page.getByLabel('Vencimento', { exact: true }).fill('2026-09-24');
  await page.getByLabel('Categoria (opcional)').selectOption({ label: 'Pets' });
  await page.getByRole('button', { name: 'Salvar despesa' }).click();
  await expect(page.getByText('Despesa cadastrada com sucesso.')).toBeVisible();
  await expect(page.getByText('Energia')).toBeVisible();
  await expect(page.getByText('Atrasada', { exact: true })).toBeVisible();
  await page.getByLabel('Buscar na descrição').fill('inexistente');
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  await expect(page.getByText('Nenhum lançamento corresponde aos filtros.')).toBeVisible();
  await page.getByRole('button', { name: 'Limpar' }).click();
  await expect(page.getByText('Energia')).toBeVisible();

  await page.goto('/membros');
  await page.getByLabel('Email do convidado').fill(guestEmail);
  await page.getByRole('button', { name: 'Enviar convite' }).click();
  await expect(page.getByText('Convite criado e envio solicitado.')).toBeVisible();
  const oldInvitationLink = await mailLink('aceitar-convite');
  await page.getByRole('button', { name: 'Reenviar e substituir link' }).click();
  await expect(page.getByText(/link anterior deixou de funcionar/)).toBeVisible();
  const invitationLink = await mailLink('aceitar-convite', oldInvitationLink);

  const guestContext = await browser.newContext();
  const guestPage = await guestContext.newPage();
  await guestPage.goto(oldInvitationLink);
  await expect(guestPage.getByText(/inválido, expirou, já foi utilizado ou foi substituído/)).toBeVisible();
  await guestPage.goto(invitationLink);
  await expect(guestPage.getByText(/histórico completo/)).toBeVisible();
  await guestPage.getByLabel('Seu nome').fill('Pessoa Convidada');
  await guestPage.getByLabel('Senha').fill(guestPassword);
  await guestPage.getByRole('button', { name: 'Criar conta e aceitar' }).click();
  await expect(guestPage.getByText(/Convite aceito/)).toBeVisible();
  await guestPage.goto('/entrar');
  await guestPage.getByRole('textbox', { name: 'Email', exact: true }).fill(guestEmail);
  await guestPage.getByLabel('Senha').fill(guestPassword);
  await guestPage.getByRole('button', { name: 'Entrar', exact: true }).click();
  await expect(guestPage.getByText(/Você entrou em/)).toContainText('Minha casa');
  await expect(guestPage.getByText(/como convidado/)).toBeVisible();
  await guestPage.getByRole('link', { name: 'Cadastrar e consultar despesas' }).click();
  await expect(guestPage.getByText('Energia')).toBeVisible();
  await guestPage.goto('/categorias');
  const pets = guestPage.locator('mat-card').filter({ hasText: 'Pets' });
  await pets.getByRole('button', { name: 'Renomear' }).click();
  await guestPage.getByLabel('Novo nome').fill('Casa');
  await guestPage.getByRole('button', { name: 'Salvar nome' }).click();
  await expect(guestPage.getByText('Categoria renomeada.')).toBeVisible();
  const house = guestPage.locator('mat-card').filter({ hasText: 'Casa' });
  await house.getByRole('button', { name: 'Arquivar' }).click();
  await expect(guestPage.getByText(/Despesas existentes foram preservadas/)).toBeVisible();
  await guestPage.goto('/despesas');
  await expect(guestPage.locator('.expense-row').filter({ hasText: 'Energia' }).getByText(/Categoria: Casa/)).toBeVisible();
  await expect(guestPage.getByLabel('Categoria (opcional)').getByRole('option', { name: 'Casa' })).toHaveCount(0);
  const assignedEnergy = guestPage.locator('.expense-row').filter({ hasText: 'Energia' });
  await assignedEnergy.getByRole('button', { name: 'Corrigir despesa' }).click();
  await guestPage.getByLabel('Responsável corrigido (opcional)').selectOption({ label: 'Pessoa Convidada' });
  await guestPage.getByRole('button', { name: 'Salvar correção' }).click();
  await expect(guestPage.getByText('Despesa corrigida com sucesso.')).toBeVisible();
  await expect(assignedEnergy.getByText(/Responsável: Pessoa Convidada/)).toBeVisible();
  await assignedEnergy.getByRole('button', { name: 'Ver histórico' }).click();
  await expect(guestPage.getByText('Despesa cadastrada', { exact: true })).toBeVisible();
  await expect(guestPage.getByText(/Responsável: Não definido → Pessoa Convidada/)).toBeVisible();
  await guestPage.getByRole('button', { name: 'Fechar histórico' }).click();
  await guestPage.getByRole('button', { name: 'Quitar despesa' }).click();
  await guestPage.getByRole('textbox', { name: 'Valor efetivamente pago' }).fill('155,00');
  await guestPage.getByLabel('Data da quitação').fill('2026-09-25');
  await guestPage.getByLabel('Pagador da quitação').selectOption({ label: 'Diego' });
  await guestPage.getByLabel('Observação da quitação').fill('Juros confirmados');
  await guestPage.getByRole('button', { name: 'Confirmar quitação' }).click();
  await expect(guestPage.getByText('Quitação registrada com sucesso.')).toBeVisible();
  await expect(guestPage.getByText('Valor pago: R$ 155,00')).toBeVisible();
  await expect(guestPage.getByText('Pagamento em 25/09/2026 por Diego.')).toBeVisible();
  await expect(guestPage.getByText('Registrado por Pessoa Convidada.')).toBeVisible();
  await guestPage.getByRole('textbox', { name: 'Descrição', exact: true }).fill('Mercado');
  await guestPage.getByRole('textbox', { name: 'Valor', exact: true }).fill('25,50');
  await guestPage.locator('form').first().getByLabel('Situação').selectOption('PAID');
  await guestPage.getByLabel('Data do pagamento').fill('2026-09-25');
  await guestPage.getByRole('button', { name: 'Salvar despesa' }).click();
  await expect(guestPage.getByText('Despesa cadastrada com sucesso.')).toBeVisible();
  const marketExpense = guestPage.locator('.expense-row').filter({ hasText: 'Mercado' });
  await expect(marketExpense).toBeVisible();
  await expect(marketExpense.getByText('Paga', { exact: true })).toBeVisible();
  await marketExpense.getByRole('button', { name: 'Desfazer quitação' }).click();
  await expect(guestPage.getByText(/corrija a despesa e informe um vencimento/)).toBeVisible();
  await expect(guestPage.getByRole('button', { name: 'Confirmar reversão' })).toBeDisabled();
  await guestPage.getByRole('button', { name: 'Voltar sem alterar' }).click();
  await marketExpense.getByRole('button', { name: 'Corrigir despesa' }).click();
  await guestPage.getByLabel('Vencimento corrigido (opcional)').fill('2026-09-25');
  await guestPage.getByRole('button', { name: 'Salvar correção' }).click();
  await expect(guestPage.getByText('Despesa corrigida com sucesso.')).toBeVisible();

  const concurrentPage = await guestContext.newPage();
  await concurrentPage.goto('/despesas');
  const firstCopy = concurrentPage.locator('.expense-row').filter({ hasText: 'Energia' });
  const staleCopy = guestPage.locator('.expense-row').filter({ hasText: 'Energia' });
  await firstCopy.getByRole('button', { name: 'Corrigir despesa' }).click();
  await staleCopy.getByRole('button', { name: 'Corrigir despesa' }).click();
  await concurrentPage.getByLabel('Descrição da correção').fill('Energia conferida');
  await concurrentPage.getByRole('button', { name: 'Salvar correção' }).click();
  await expect(concurrentPage.getByText('Despesa corrigida com sucesso.')).toBeVisible();

  await guestPage.getByLabel('Descrição da correção').fill('Energia final');
  await guestPage.getByRole('button', { name: 'Salvar correção' }).click();
  await expect(guestPage.getByText(/Outra alteração foi salva antes da sua/)).toBeVisible();
  await expect(guestPage.getByText(/Energia conferida/)).toBeVisible();
  await expect(guestPage.getByLabel('Descrição da correção')).toHaveValue('Energia final');
  await guestPage.getByRole('button', { name: 'Revisei: usar versão atual mantendo meus campos' }).click();
  await guestPage.getByRole('button', { name: 'Salvar correção' }).click();
  await expect(guestPage.getByText('Despesa corrigida com sucesso.')).toBeVisible();
  await expect(guestPage.locator('.expense-row').filter({ hasText: 'Energia final' })).toBeVisible();
  await concurrentPage.close();

  const finalEnergy = guestPage.locator('.expense-row').filter({ hasText: 'Energia final' });
  await finalEnergy.getByRole('button', { name: 'Desfazer quitação' }).click();
  await guestPage.getByLabel('Motivo obrigatório').fill('Pagamento lançado na conta errada');
  await guestPage.getByRole('button', { name: 'Confirmar reversão' }).click();
  await expect(guestPage.getByText(/voltou a ficar pendente/)).toBeVisible();
  await expect(finalEnergy.getByRole('button', { name: 'Quitar despesa' })).toBeVisible();
  await finalEnergy.getByRole('button', { name: 'Quitar despesa' }).click();
  await guestPage.getByRole('button', { name: 'Confirmar quitação' }).click();
  await expect(guestPage.getByText('Quitação registrada com sucesso.')).toBeVisible();

  await finalEnergy.getByRole('button', { name: 'Desfazer quitação' }).click();
  await guestPage.getByLabel('Motivo obrigatório').fill('Despesa será cancelada');
  await guestPage.getByRole('button', { name: 'Confirmar reversão' }).click();
  await expect(guestPage.getByText(/voltou a ficar pendente/)).toBeVisible();
  await finalEnergy.getByRole('button', { name: 'Cancelar despesa' }).click();
  await guestPage.getByLabel('Motivo obrigatório').fill('Cobrança duplicada');
  await guestPage.getByRole('button', { name: 'Confirmar cancelamento' }).click();
  await expect(guestPage.getByText(/removida da lista ativa/)).toBeVisible();
  await expect(finalEnergy).toHaveCount(0);
  await expect(guestPage.getByText('Situação atual: Cancelada')).toBeVisible();
  await expect(guestPage.getByText('Quitação desfeita', { exact: false })).toHaveCount(2);
  await expect(guestPage.getByText('Despesa cancelada', { exact: true })).toBeVisible();

  await guestPage.getByRole('button', { name: 'Fechar histórico' }).click();
  for (const [description, amount] of [['Água', '80,00'], ['Internet', '99,90']] as const) {
    await guestPage.getByRole('textbox', { name: 'Descrição', exact: true }).fill(description);
    await guestPage.getByRole('textbox', { name: 'Valor', exact: true }).fill(amount);
    await guestPage.getByLabel('Vencimento', { exact: true }).fill('2026-09-28');
    await guestPage.getByRole('button', { name: 'Salvar despesa' }).click();
    await expect(guestPage.getByText('Despesa cadastrada com sucesso.')).toBeVisible();
  }
  await guestPage.getByLabel('Selecionar para quitação em lote: Água').check();
  await guestPage.getByLabel('Selecionar para quitação em lote: Internet').check();
  await guestPage.getByRole('button', { name: 'Quitar selecionadas (2)' }).click();
  await expect(guestPage.getByText('2 lançamentos · total R$ 179,90')).toBeVisible();
  await guestPage.getByLabel('Data da quitação do lote').fill('2026-10-01');
  await guestPage.getByLabel('Pagador do lote').selectOption({ label: 'Diego' });
  await guestPage.getByLabel(/Confirmo a quitação integral/).check();

  const batchConflictPage = await guestContext.newPage();
  await batchConflictPage.goto('/despesas');
  const waterCopy = batchConflictPage.locator('.expense-row').filter({ hasText: 'Água' });
  await waterCopy.getByRole('button', { name: 'Corrigir despesa' }).click();
  await batchConflictPage.getByLabel('Descrição da correção').fill('Água conferida');
  await batchConflictPage.getByRole('button', { name: 'Salvar correção' }).click();
  await expect(batchConflictPage.getByText('Despesa corrigida com sucesso.')).toBeVisible();
  await batchConflictPage.close();

  await guestPage.getByRole('button', { name: 'Quitar todos ou nenhum' }).click();
  await expect(guestPage.getByText(/lote inteiro foi rejeitado/)).toBeVisible();
  await expect(guestPage.getByLabel('Data da quitação do lote')).toHaveValue('2026-10-01');
  await expect(guestPage.locator('.expense-row').filter({ hasText: 'Internet' })
    .getByRole('button', { name: 'Quitar despesa' })).toBeVisible();

  await guestPage.reload();
  await guestPage.getByLabel('Selecionar para quitação em lote: Água conferida').check();
  await guestPage.getByLabel('Selecionar para quitação em lote: Internet').check();
  await guestPage.getByRole('button', { name: 'Quitar selecionadas (2)' }).click();
  await guestPage.getByLabel('Data da quitação do lote').fill('2026-10-01');
  await guestPage.getByLabel('Pagador do lote').selectOption({ label: 'Diego' });
  await guestPage.getByLabel(/Confirmo a quitação integral/).check();
  await guestPage.getByRole('button', { name: 'Quitar todos ou nenhum' }).click();
  await expect(guestPage.getByText(/2 lançamentos quitados no lote/)).toBeVisible();
  await expect(guestPage.locator('.expense-row').filter({ hasText: 'Água conferida' })
    .getByText('Paga', { exact: true })).toBeVisible();
  await expect(guestPage.locator('.expense-row').filter({ hasText: 'Internet' })
    .getByText('Paga', { exact: true })).toBeVisible();

  await guestPage.goto('/painel');
  await expect(guestPage.getByRole('heading', { name: 'Painel por vencimento' })).toBeVisible();
  await guestPage.getByLabel('Ir para o mês').fill('2026-09');
  await expect(guestPage.getByRole('heading', { name: /setembro de 2026/i })).toBeVisible();
  await expect(guestPage.getByTestId('planned-total')).toHaveText(/R\$\s*205,40/);
  await expect(guestPage.getByTestId('paid-total')).toHaveText(/R\$\s*205,40/);
  await expect(guestPage.getByTestId('pending-total')).toHaveText(/R\$\s*0,00/);
  await expect(guestPage.getByText('Energia final')).toHaveCount(0);
  await expect(guestPage.getByText('Mercado')).toBeVisible();
  await guestPage.getByLabel('Situação').selectOption('CANCELLED');
  await guestPage.getByRole('button', { name: 'Aplicar filtros' }).click();
  await expect(guestPage.getByText('Energia final')).toBeVisible();
  await expect(guestPage.getByTestId('planned-total')).toHaveText(/R\$\s*0,00/);

  await guestPage.getByRole('link', { name: 'Pagamentos do mês' }).first().click();
  await expect(guestPage.getByRole('heading', { name: 'Pagamentos do mês' })).toBeVisible();
  await guestPage.getByLabel('Ir para o mês').fill('2026-09');
  await expect(guestPage.getByRole('heading', { name: /setembro de 2026/i })).toBeVisible();
  await expect(guestPage.getByText('Base temporal: data do pagamento')).toBeVisible();
  await expect(guestPage.getByTestId('payments-paid-total')).toHaveText(/R\$\s*25,50/);
  await expect(guestPage.getByTestId('payments-count')).toContainText('1 pagamento(s)');
  await expect(guestPage.getByText('Energia final')).toHaveCount(0);
  await guestPage.getByRole('button', { name: 'Próximo mês' }).click();
  await expect(guestPage.getByRole('heading', { name: /outubro de 2026/i })).toBeVisible();
  await expect(guestPage.getByTestId('payments-paid-total')).toHaveText(/R\$\s*179,90/);
  await expect(guestPage.getByTestId('payments-count')).toContainText('2 pagamento(s)');
  await expect(guestPage.getByText(/Em lote, por Pessoa Convidada/)).toHaveCount(2);
  await expect(guestPage.getByText('Pago por Diego')).toHaveCount(2);
  const anonymousPaymentsStatus = await guestPage.evaluate(async () => (await fetch('/api/v1/reports/payments?month=2026-10', {
    credentials: 'omit',
  })).status);
  expect(anonymousPaymentsStatus).toBe(401);
  const anonymousContext = await browser.newContext();
  const anonymousPage = await anonymousContext.newPage();
  await anonymousPage.goto('/entrar');
  const anonymousDashboardStatus = await anonymousPage.evaluate(async () => (await fetch('/api/v1/reports/due-dashboard?month=2026-09', {
    credentials: 'include',
  })).status);
  expect(anonymousDashboardStatus).toBe(401);
  await anonymousContext.close();

  await guestPage.goto('/membros');
  await expect(guestPage.getByText(/Como convidado/)).toBeVisible();

  await page.reload();
  page.once('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Transferir administração' }).click();
  await expect(page.getByText(/Administração transferida/)).toBeVisible();
  await expect(page.getByText(/Como convidado/)).toBeVisible();

  await guestPage.reload();
  await expect(guestPage.getByRole('button', { name: 'Transferir administração' })).toBeVisible();
  guestPage.once('dialog', dialog => dialog.accept());
  await guestPage.getByRole('button', { name: 'Transferir administração' }).click();
  await expect(guestPage.getByText(/Administração transferida/)).toBeVisible();

  await page.reload();
  await expect(page.getByRole('button', { name: 'Remover membro' })).toBeVisible();
  await guestPage.reload();
  guestPage.once('dialog', dialog => dialog.accept());
  await guestPage.getByRole('button', { name: 'Sair deste espaço' }).click();
  await expect(guestPage.getByRole('region', { name: 'Entrar' })).toBeVisible();
  const guestRevokedStatus = await guestPage.evaluate(async () => (await fetch('/api/v1/identity/me', {
    credentials: 'include', headers: { 'X-User-Activity': 'true' },
  })).status);
  expect(guestRevokedStatus).toBe(401);
  await page.reload();
  await expect(page.getByLabel('Email do convidado')).toBeVisible();
  await guestContext.close();

  const recoveryContext = await browser.newContext();
  const recoveryPage = await recoveryContext.newPage();
  await recoveryPage.goto('/recuperar-acesso');
  await recoveryPage.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
  await recoveryPage.getByRole('button', { name: 'Enviar recuperação' }).click();
  await expect(recoveryPage.getByText(/Se a conta estiver disponível/)).toBeVisible();
  const resetLink = await mailLink('redefinir-senha');
  await recoveryPage.goto(resetLink);
  await recoveryPage.getByLabel('Nova senha').fill(newPassword);
  await recoveryPage.getByRole('button', { name: 'Redefinir senha' }).click();
  await expect(recoveryPage.getByText(/sessões anteriores encerradas/)).toBeVisible();

  const revokedStatus = await page.evaluate(async () => {
    const response = await fetch('/api/v1/identity/me', {
      credentials: 'include',
      headers: { 'X-User-Activity': 'true' },
    });
    return response.status;
  });
  expect(revokedStatus).toBe(401);

  await recoveryPage.goto(resetLink);
  await recoveryPage.getByLabel('Nova senha').fill('outra senha segura 2026');
  await recoveryPage.getByRole('button', { name: 'Redefinir senha' }).click();
  await expect(recoveryPage.getByText(/inválido, expirou ou já foi utilizado/)).toBeVisible();
  await recoveryContext.close();

  await page.goto('/entrar');
  await page.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
  await page.getByLabel('Senha').fill(newPassword);
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await expect(page.getByText(/Você entrou em/)).toContainText('Minha casa');

  await page.goto('/recorrencias');
  await page.getByLabel('Descrição').fill('Condomínio recorrente');
  await page.getByRole('textbox', { name: 'Valor', exact: true }).fill('500,00');
  await page.getByLabel('Primeiro vencimento').fill('2027-01-31');
  await page.getByRole('button', { name: 'Calcular próximas datas' }).click();
  await expect(page.getByText('2027-02-28')).toBeVisible();
  await expect(page.getByText('2027-03-31')).toBeVisible();
  await page.getByRole('button', { name: 'Cadastrar recorrência' }).click();
  await expect(page.getByText(/em até 30 segundos/)).toBeVisible();
  await expect(page.locator('mat-card-title').getByText('Condomínio recorrente', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cadastrar recorrência' })).toBeEnabled();
  const futureForecast = page.locator('article.forecast').filter({ hasText: 'Condomínio recorrente' }).first();
  await expect(futureForecast.getByText('Previsão fixa')).toBeVisible();
  page.once('dialog', dialog => dialog.accept());
  await futureForecast.getByRole('button', { name: 'Antecipar lançamento' }).click();
  await expect(page.getByText(/Lançamento antecipado sem alterar/)).toBeVisible();
  await expect(page.locator('article.forecast').filter({ hasText: 'Condomínio recorrente' }).first().getByText('Lançamento confirmado')).toBeVisible();
  await page.goto('/despesas');
  await page.getByLabel('Buscar na descrição').fill('Condomínio recorrente');
  await page.getByLabel('Data inicial').fill('2027-01-01');
  await page.getByLabel('Data final').fill('2027-01-31');
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  await expect(page.getByText('Condomínio recorrente', { exact: true })).toBeVisible();
  await page.goto('/recorrencias');

  await page.getByLabel('Descrição').fill('Energia estimada automática');
  await page.getByRole('textbox', { name: 'Valor', exact: true }).fill('180,50');
  await page.getByLabel('Tipo do valor').selectOption('VARIABLE_ESTIMATE');
  await page.getByLabel('Primeiro vencimento').fill('2026-09-30');
  await page.getByRole('button', { name: 'Cadastrar recorrência' }).click();
  await expect(page.locator('mat-card-title').getByText('Energia estimada automática', { exact: true })).toBeVisible();
  await page.goto('/despesas');
  await expect.poll(() => page.evaluate(async () => {
    const response = await fetch('/api/v1/expenses?search=Energia%20estimada%20autom%C3%A1tica', {
      credentials: 'include', headers: { 'X-User-Activity': 'true' },
    });
    if (!response.ok) return false;
    const result = await response.json() as { content?: { description: string }[] };
    return result.content?.some(item => item.description === 'Energia estimada automática') ?? false;
  }), { timeout: 45_000 }).toBe(true);
  await page.reload();
  await expect(page.getByText('Energia estimada automática', { exact: true })).toBeVisible();
  await expect(page.getByText(/Gerada por recorrência · valor estimado a confirmar/)).toBeVisible();
  const estimatedRow = page.locator('mat-card.expense-row').filter({ hasText: 'Energia estimada automática' });
  await estimatedRow.getByRole('button', { name: 'Confirmar valor da cobrança' }).click();
  await page.getByLabel('Valor confirmado da cobrança').fill('195,30');
  await page.locator('.charge-confirmation-card').getByRole('button', { name: 'Confirmar valor da cobrança' }).click();
  await expect(page.getByText(/continua pendente de quitação/)).toBeVisible();
  await expect(estimatedRow.getByText(/Valor da cobrança confirmado por Diego/)).toBeVisible();
  await expect(estimatedRow.getByText(/estimativa anterior R\$\s*180,50/)).toBeVisible();
  await page.goto('/recorrencias');
  const nextEstimate = page.locator('article.forecast').filter({ hasText: 'Energia estimada automática' })
    .filter({ hasText: 'Previsão estimada' }).first();
  await expect(nextEstimate.getByText(/R\$ 195\.30/)).toBeVisible();
  await nextEstimate.getByRole('button', { name: 'Confirmar valor da cobrança' }).click();
  await page.locator('form.forecast-confirm').getByLabel('Valor confirmado da cobrança').fill('201,00');
  await page.locator('form.forecast-confirm').getByRole('button', { name: 'Confirmar valor' }).click();
  await expect(page.getByText(/confirmado em R\$ 201\.00/)).toBeVisible();

  // H04.5: change "este e os próximos" and closure, always after reviewing the impact.
  const variableCard = page.locator('mat-card').filter({ hasText: 'Estimativa variável a confirmar' });
  await variableCard.getByRole('button', { name: 'Alterar a partir de um vencimento' }).click();
  await variableCard.getByLabel('Descrição').fill('Energia da casa');
  await variableCard.getByRole('button', { name: 'Revisar impacto' }).click();
  await expect(variableCard.getByText(/Vale a partir de/)).toBeVisible();
  await variableCard.getByRole('button', { name: 'Confirmar alteração' }).click();
  await expect(page.getByText(/alterada a partir de/)).toBeVisible();
  await expect(variableCard.getByText('Histórico de alterações')).toBeVisible();
  await variableCard.getByRole('button', { name: 'Encerrar recorrência' }).click();
  await variableCard.getByLabel('Motivo do encerramento').fill('Troca de fornecedor');
  await variableCard.getByRole('button', { name: 'Revisar impacto' }).click();
  await variableCard.getByRole('button', { name: 'Confirmar encerramento' }).click();
  await expect(variableCard.getByText(/Encerrada: último vencimento/)).toBeVisible();
  // The reason appears in the closure notice and again in the change history.
  await expect(variableCard.locator('p.closed')).toContainText('motivo: Troca de fornecedor');

  // H05.1: installment purchase reviewed through the backend preview, then materialized as n/N entries.
  await page.goto('/compras-parceladas');
  await page.getByLabel('Descrição').fill('Sofá parcelado');
  await page.getByLabel('Valor total').fill('100,00');
  await page.getByLabel('Quantidade de parcelas').fill('3');
  await page.getByLabel('Vencimento da primeira parcela').fill('2027-01-31');
  await page.getByRole('button', { name: 'Revisar parcelas' }).click();
  await expect(page.getByRole('row', { name: /2\/3 2027-02-28 R\$ 33\.33/ })).toBeVisible();
  await expect(page.getByText(/A última parcela tem R\$ 0\.01 a mais/)).toBeVisible();
  await page.getByRole('button', { name: 'Confirmar e criar 3 parcelas' }).click();
  await expect(page.getByText(/criada com 3 parcelas pendentes, de 2027-01-31 a 2027-03-31, somando R\$ 100\.00/)).toBeVisible();
  // H05.2: progress comes from the installments; paying selected ones uses the atomic batch of Despesas.
  await expect(page.getByText('0 de 3 pagas · 3 pendentes')).toBeVisible();
  await page.getByRole('button', { name: 'Ver parcelas de Sofá parcelado' }).click();
  await page.getByLabel('Selecionar parcela 1/3').check();
  await page.getByRole('button', { name: 'Quitar selecionadas (1)' }).click();
  await page.getByLabel(/Confirmo a quitação integral da parcela selecionada/).check();
  await page.getByRole('button', { name: 'Confirmar quitação' }).click();
  await expect(page.getByText('1 parcela quitada.')).toBeVisible();
  await expect(page.getByText('1 de 3 pagas · 2 pendentes')).toBeVisible();
  await expect(page.getByRole('row', { name: /1\/3 2027-01-31 R\$ 33\.33 Paga em/ })).toBeVisible();
  // H05.3: the server reviews the impact; the paid 1/3 is preserved and the following pending ones move month by month.
  await page.getByRole('button', { name: 'Alterar parcelas pendentes' }).click();
  await page.getByLabel('A partir da parcela').selectOption({ label: '2/3 · 2027-02-28' });
  await page.getByLabel('Esta e as próximas pendentes').check();
  await page.getByLabel('Alterar vencimento').check();
  await page.getByLabel('Novo vencimento').fill('2027-02-10');
  await page.getByRole('button', { name: 'Revisar impacto' }).click();
  await expect(page.getByText('2 parcelas serão alteradas.')).toBeVisible();
  await expect(page.getByText('Vencimento: 2027-03-31 → 2027-03-10')).toBeVisible();
  await expect(page.getByText('1 (paga, não muda)')).toBeVisible();
  await page.getByRole('button', { name: 'Confirmar alteração' }).click();
  await expect(page.getByText('Alteração aplicada a 2 parcelas; 1 preservada.')).toBeVisible();
  await expect(page.getByRole('row', { name: /3\/3 2027-03-10 R\$ 33\.34 Pendente/ })).toBeVisible();
  // Cancelling the last one with a replacement purchase for the remainder happens in one transaction.
  await page.getByLabel('Selecionar parcela 3/3').check();
  await page.getByRole('button', { name: 'Cancelar selecionadas (1)' }).click();
  await page.getByLabel('Motivo do cancelamento').fill('Loja renegociou o saldo');
  await page.getByLabel(/Criar nova compra com o restante/).check();
  await page.getByRole('button', { name: 'Revisar cancelamento' }).click();
  await expect(page.getByText('1 parcela será cancelada, somando R$ 33.34.')).toBeVisible();
  await expect(page.getByText(/Nova compra “Sofá parcelado \(restante\)”: R\$ 33\.34 em 2 parcelas, de 2027-03-10 a 2027-04-10/)).toBeVisible();
  await page.getByRole('button', { name: 'Confirmar cancelamento' }).click();
  await expect(page.getByText('1 parcela cancelada; 2 preservadas. Nova compra “Sofá parcelado (restante)” criada com 2 parcelas.')).toBeVisible();
  await expect(page.getByRole('row', { name: /3\/3 2027-03-10 R\$ 33\.34 Cancelada/ })).toBeVisible();
  await page.getByRole('link', { name: 'Ver parcelas em Despesas' }).click();
  await page.getByLabel('Buscar na descrição').fill('Sofá parcelado');
  // Without dates the list shows only the current month (H03.4); the installments fall in 2027.
  await page.getByLabel('Data inicial').fill('2027-01-01');
  await page.getByLabel('Data final').fill('2027-03-31');
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  await expect(page.getByText('Parcela 1/3 · compra parcelada')).toBeVisible();
  await expect(page.getByText('Parcela 2/3 · compra parcelada')).toBeVisible();

  // H06.3: planning over the real data; the cancelled 3/3 is out and nothing is counted twice.
  await page.goto('/planejamento');
  await expect(page.getByRole('heading', { name: 'Planejamento dos próximos meses' })).toBeVisible();
  await page.getByLabel('Buscar na descrição').fill('Sofá parcelado');
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  await expect(page.getByTestId('planning-total')).toHaveText(/R\$\s*100,00/);
  await expect(page.getByTestId('planning-materialized')).toContainText('(4)');
  await expect(page.getByTestId('planning-forecast')).toContainText('(0)');
  await page.getByRole('button', { name: 'Ver janeiro de 2027' }).click();
  await expect(page.getByTestId('planning-month-title')).toHaveText(/janeiro de 2027: R\$\s*33,33/i);
  await expect(page.getByTestId('planning-item')).toHaveCount(1);
  await expect(page.getByTestId('planning-item')).toContainText('Parcela 1/3');
  await expect(page.getByTestId('planning-item')).toContainText('Paga em');
  await page.getByLabel('Buscar na descrição').fill('Condomínio recorrente');
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  // January was anticipated: only the entry shows; February onwards are forecasts of the same recurrence.
  await expect(page.getByTestId('planning-month-title')).toHaveText(/janeiro de 2027: R\$\s*500,00/i);
  await expect(page.getByTestId('planning-item')).toHaveCount(1);
  await expect(page.getByTestId('planning-item')).toContainText('Lançamento');
  await page.getByRole('button', { name: 'Ver fevereiro de 2027' }).click();
  await expect(page.getByTestId('planning-item')).toHaveCount(1);
  await expect(page.getByTestId('planning-item')).toContainText('Previsão (ainda não gerada)');
  const anonymousPlanningStatus = await page.evaluate(async () => (await fetch('/api/v1/reports/planning', {
    credentials: 'omit',
  })).status);
  expect(anonymousPlanningStatus).toBe(401);

  // H06.4: forecasts go to their own file; January (already an entry) is not a forecast.
  const forecastDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Exportar previsões (CSV)' }).click();
  const forecastCsv = readFileSync((await (await forecastDownload).path())!).toString('utf8');
  const forecastLines = forecastCsv.split('\r\n').filter(line => line !== '');
  expect(forecastCsv.startsWith('\uFEFF"Descrição";"Categoria";"Vencimento previsto";"Valor previsto"')).toBe(true);
  expect(forecastLines[1]).toMatch(/^"Condomínio recorrente";;28\/02\/2027;500,00;Não;;Previsão de recorrência;[0-9a-f-]{36}$/);
  expect(forecastCsv).not.toContain('31/01/2027');
  await expect(page.getByTestId('csv-export-message')).toContainText(`baixado com ${forecastLines.length - 1} registro`);

  // H06.4: the expense CSV has every entry of the applied selection, cancelled included when asked.
  await page.goto('/despesas');
  await page.getByLabel('Buscar na descrição').fill('Sofá parcelado');
  await page.getByLabel('Data inicial').fill('2027-01-01');
  await page.getByLabel('Data final').fill('2027-04-30');
  await page.locator('select[formcontrolname="status"]').last().selectOption('ALL');
  await page.getByRole('button', { name: 'Aplicar filtros' }).click();
  await expect(page.getByText('5 despesas')).toBeVisible();
  const expenseDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Exportar CSV' }).click();
  const download = await expenseDownload;
  expect(download.suggestedFilename()).toBe('despesas_vencimento_2027-01-01_a_2027-04-30.csv');
  const expenseCsv = readFileSync((await download.path())!).toString('utf8');
  const rows = expenseCsv.split('\r\n').filter(line => line !== '').slice(1);
  expect(rows).toHaveLength(5);
  expect(rows[0]).toMatch(/^"Sofá parcelado";;31\/01\/2027;33,33;Não;Paga;33,33;\d{2}\/\d{2}\/\d{4};;"Diego";Parcela;1 de 3;/);
  expect(rows[1]).toMatch(/^"Sofá parcelado";;10\/02\/2027;33,33;Não;Pendente;;;;;Parcela;2 de 3;/);
  expect(rows.filter(row => row.includes(';Cancelada;'))).toHaveLength(1);
  expect(rows.filter(row => row.startsWith('"Sofá parcelado (restante)";;'))).toHaveLength(2);
  await expect(page.getByTestId('csv-export-message')).toHaveText(
    'Arquivo despesas_vencimento_2027-01-01_a_2027-04-30.csv baixado com 5 registros.');
  const anonymousExportStatus = await page.evaluate(async () => (await fetch('/api/v1/reports/expenses/export', {
    credentials: 'omit',
  })).status);
  expect(anonymousExportStatus).toBe(401);

  // H07.1: closing the current month saves its snapshot and leaves every expense as it was.
  await page.goto('/painel');
  const dashboardPending = await page.getByTestId('pending-total').textContent();
  await page.goto('/fechamento');
  await expect(page.getByTestId('closing-status')).toContainText('Mês não fechado');
  const currentPlanned = await page.getByTestId('current-planned').textContent();
  await page.getByRole('button', { name: 'Fechar mês…' }).click();
  if (await page.getByTestId('pending-warning').isVisible())
    await page.getByLabel('Estou ciente das pendências e quero fechar mesmo assim.').check();
  await page.getByRole('button', { name: 'Confirmar fechamento' }).click();
  await expect(page.getByTestId('closing-status')).toContainText('Mês fechado');
  await expect(page.getByTestId('closing-status')).toContainText('versão 1');
  await expect(page.getByTestId('closing-status')).toContainText('Diego');
  await expect(page.getByTestId('saved-planned')).toHaveText(currentPlanned!);
  await page.reload();
  await expect(page.getByTestId('saved-planned')).toHaveText(currentPlanned!);
  await page.goto('/painel');
  await expect(page.getByTestId('pending-total')).toHaveText(dashboardPending!);
  const anonymousClosingStatus = await page.evaluate(async () => (await fetch('/api/v1/reports/closings/2026-09', {
    credentials: 'omit',
  })).status);
  expect(anonymousClosingStatus).toBe(401);

  // H07.2: a later inclusion in the closed month flags the closing and never rewrites the saved snapshot.
  const closedMonth = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', year: 'numeric', month: '2-digit' })
    .format(new Date());
  await page.goto('/despesas');
  await page.getByRole('textbox', { name: 'Descrição', exact: true }).fill('Ajuste pós-fechamento');
  await page.getByRole('textbox', { name: 'Valor', exact: true }).fill('12,34');
  await page.locator('form').first().getByLabel('Situação').selectOption('PAID');
  await page.getByLabel('Data do pagamento').fill(`${closedMonth}-01`);
  await page.getByRole('button', { name: 'Salvar despesa' }).click();
  await expect(page.getByText('Despesa cadastrada com sucesso.')).toBeVisible();
  await page.goto('/fechamento');
  await expect(page.getByTestId('closing-outdated')).toContainText('1 diferença(s)');
  await expect(page.getByTestId('closing-changes')).toContainText('Ajuste pós-fechamento');
  await expect(page.getByTestId('closing-changes')).toContainText('Entrou no mês depois do fechamento');
  await expect(page.getByTestId('saved-planned')).toHaveText(currentPlanned!);
  await expect(page.getByTestId('current-planned')).not.toHaveText(currentPlanned!);
  await expect(page.getByTestId(`closing-item-${closedMonth}`)).toContainText('Alterado depois');

  // H07.3: a new version takes the current data; version 1 keeps the values of the first closing.
  const updatedPlanned = await page.getByTestId('current-planned').textContent();
  await page.getByRole('button', { name: 'Gerar nova versão…' }).click();
  if (await page.getByTestId('pending-warning').isVisible())
    await page.getByLabel('Estou ciente das pendências e quero fechar mesmo assim.').check();
  await page.getByRole('button', { name: 'Gerar versão 2' }).click();
  await expect(page.getByTestId('closing-status')).toContainText('versão 2 (vigente)');
  await expect(page.getByTestId('closing-up-to-date')).toBeVisible();
  await expect(page.getByTestId('saved-planned')).toHaveText(updatedPlanned!);
  await expect(page.getByTestId('version-2')).toContainText('Vigente');
  await page.getByTestId('version-1').getByRole('button', { name: 'Ver retrato' }).click();
  await expect(page.getByTestId('old-version')).toContainText('Versão 1 — anterior');
  await expect(page.getByTestId('version-planned')).toHaveText(currentPlanned!);
  await page.reload();
  await expect(page.getByTestId('closing-status')).toContainText('versão 2 (vigente)');
  await expect(page.getByTestId(`closing-item-${closedMonth}`)).toContainText('Atualizado');

  await page.goto('/entrar');
  await page.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
  await page.getByLabel('Senha').fill(newPassword);
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await page.getByRole('button', { name: 'Encerrar todas as sessões' }).click();
  await expect(page.getByRole('region', { name: 'Entrar' })).toBeVisible();
});

async function mailLink(
  path: 'confirmar-email' | 'redefinir-senha' | 'aceitar-convite',
  excluded = '',
): Promise<string> {
  const mailpit = await playwrightRequest.newContext({ baseURL: 'http://127.0.0.1:8025' });
  try {
    let link = '';
    await expect.poll(async () => {
      const listResponse = await mailpit.get('/api/v1/messages');
      if (!listResponse.ok()) return '';
      const list = await listResponse.json() as { messages?: { ID: string }[] };
      for (const message of list.messages ?? []) {
        const response = await mailpit.get(`/api/v1/message/${message.ID}`);
        if (!response.ok()) continue;
        const body = await response.json() as { Text?: string };
        const match = body.Text?.match(new RegExp(`http://localhost:8080/${path}\\?token=[A-Za-z0-9_-]+`));
        if (match && match[0] !== excluded) {
          link = match[0];
          return link;
        }
      }
      return '';
    }, { timeout: 15_000 }).not.toBe('');
    return link;
  } finally {
    await mailpit.dispose();
  }
}
