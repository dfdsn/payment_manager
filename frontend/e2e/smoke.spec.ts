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
  await page.goto('/');
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
