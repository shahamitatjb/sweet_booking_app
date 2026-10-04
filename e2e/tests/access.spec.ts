import { expect, test } from '@playwright/test';
import { resetAndSeed, type Seed } from '../support/db';
import { signIn } from '../support/auth';

let seed: Seed;
test.beforeEach(async () => {
  seed = await resetAndSeed();
});

for (const path of ['/admin', '/admin/settings', '/staff/counter']) {
  test(`signed-out visitors to ${path} are asked to sign in`, async ({ page }) => {
    await page.goto(path);
    await expect(page.getByRole('heading', { name: 'Not signed in' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Staff login' })).toHaveAttribute('href', '/staff/login');
  });
}

test('counter staff cannot use the admin dashboard', async ({ context, page }) => {
  await signIn(context, seed.counter);
  await page.goto('/admin');
  await expect(page.getByRole('main').getByRole('alert')).toHaveText('Access denied');
});

test('signing out ends the staff session', async ({ context, page }) => {
  await signIn(context, seed.admin);
  await page.goto('/admin');
  await expect(page.locator('.stat-grid')).toBeVisible();

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto('/admin');
  await expect(page.getByRole('heading', { name: 'Not signed in' })).toBeVisible();
});

test('the staff login page offers Google sign-in', async ({ page }) => {
  await page.goto('/staff/login');
  await expect(page.locator('a[href="/oauth2/authorization/google"]')).toBeVisible();
});
