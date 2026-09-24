import { expect, request as playwrightRequest, test } from '@playwright/test';

const email = 'admin@example.com';
const initialPassword = 'frase segura 2026';
const newPassword = 'nova senha segura 2026';

test('runs setup, email confirmation, login, reset and session revocation against real services', async ({ browser, page }) => {
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

async function mailLink(path: 'confirmar-email' | 'redefinir-senha'): Promise<string> {
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
        if (match) {
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
