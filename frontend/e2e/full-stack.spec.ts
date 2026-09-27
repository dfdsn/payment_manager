import { expect, request as playwrightRequest, test } from '@playwright/test';

const email = 'admin@example.com';
const initialPassword = 'frase segura 2026';
const newPassword = 'nova senha segura 2026';
const guestEmail = 'guest@example.com';
const guestPassword = 'senha convidada 2026';

test('runs setup, email confirmation, login, reset and session revocation against real services', async ({ browser, page }) => {
  test.setTimeout(60_000);
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
