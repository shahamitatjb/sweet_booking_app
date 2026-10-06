import { expect, test } from '@playwright/test';
import { resetAndSeed, scalar, type Seed } from '../support/db';
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
  await expect(page.getByRole('main').getByRole('alert')).toHaveText('Forbidden');
  // Give the dashboard request time to settle: no empty stats may render behind the error.
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.stat-grid')).toHaveCount(0);
});

for (const role of ['admin', 'treasurer'] as const) {
  test(`${role}s see the dashboard but Settings is super admin only`, async ({ context, page }) => {
    await signIn(context, seed[role]);
    await page.goto('/admin');
    await expect(page.locator('.stat-grid')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Settings' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Counter' }).first()).toBeVisible();

    await page.goto('/admin/settings');
    await expect(page.getByRole('heading', { name: 'Super Admin only' })).toBeVisible();
    await expect(page.getByLabel('Page title')).toHaveCount(0);
  });
}

test('super admins see Settings in the navigation', async ({ context, page }) => {
  await signIn(context, seed.superAdmin);
  await page.goto('/admin');
  await expect(page.getByRole('link', { name: 'Settings' }).first()).toBeVisible();
});

test('a session that expires mid-edit is reported, not shown as saved', async ({ context, page }) => {
  await signIn(context, seed.superAdmin);
  await page.goto('/admin/settings');
  await expect(page.getByLabel('Page title')).toHaveValue('Diwali Sweets Booking');
  await page.getByLabel('Page title').fill('Lost change');
  await context.clearCookies(); // session gone (expired / signed out elsewhere)

  await page.getByRole('button', { name: 'Save settings' }).click();
  await expect(page.locator('.card.error')).toBeVisible();
  await expect(page.getByText(/^Saved \d+ setting/)).toHaveCount(0);
  expect(await scalar<string>("SELECT value FROM settings WHERE key = 'title' AND language = 'en'")).toBe('Diwali Sweets Booking');
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
