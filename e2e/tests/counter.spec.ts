import { expect, test } from '@playwright/test';
import { resetAndSeed, scalar, type Seed } from '../support/db';
import { signIn } from '../support/auth';
import { CUSTOMER } from '../support/api';

let seed: Seed;
test.beforeEach(async ({ context, page }) => {
  seed = await resetAndSeed();
  await signIn(context, seed.counter);
  // Count print dialogs: the counter flow must not open one on its own.
  await page.addInitScript(() => {
    (window as any).__printed = 0;
    window.print = () => {
      (window as any).__printed++;
    };
  });
});

test('counter staff issue a cash booking and land on the receipt without auto-print', async ({ page }) => {
  await page.goto('/staff/counter');
  await expect(page.getByText('Taking booking as Chetan Counter')).toBeVisible();

  const issue = page.getByRole('button', { name: /Confirm cash & issue receipt/ });
  await expect(issue).toBeDisabled();

  await page.getByLabel('Customer name').fill(CUSTOMER.name);
  await page.getByLabel('Mobile').fill(CUSTOMER.mobile);
  await page.getByLabel('Address').fill(CUSTOMER.address);
  await page.getByLabel('Pin code').fill(CUSTOMER.pinCode);
  await page.getByRole('button', { name: 'Add one Besan Ladoo' }).click();
  await page.getByRole('button', { name: 'Add one Besan Ladoo' }).click();
  await expect(page.getByText('₹500.00 due')).toBeVisible();
  await issue.click();

  await expect(page).toHaveURL(/\/receipt\/JB-0001\?from=counter$/);
  await expect(page.locator('.done-banner')).toContainText('JB-0001');
  await expect(page.getByText('Booked by: Chetan Counter')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Print', exact: true })).toBeVisible();
  await page.waitForTimeout(1000);
  expect(await page.evaluate(() => (window as any).__printed)).toBe(0);
  await expect(page.getByText(/Verify:|Signature:/)).toHaveCount(0);
  expect(await scalar<string>('SELECT payment_method FROM orders')).toBe('cash');
});

test('UPI needs the 12-digit UTR, which is stored and printed', async ({ page }) => {
  await page.goto('/staff/counter');
  await page.getByRole('radio', { name: /UPI/ }).click();
  await page.getByLabel('Customer name').fill(CUSTOMER.name);
  await page.getByLabel('Mobile').fill(CUSTOMER.mobile);
  await page.getByLabel('Address').fill(CUSTOMER.address);
  await page.getByLabel('Pin code').fill(CUSTOMER.pinCode);
  await page.getByRole('button', { name: 'Add one Kaju Barfi' }).click();
  const issue = page.getByRole('button', { name: /Confirm UPI & issue receipt/ });
  await page.getByLabel('UPI transaction ID (UTR)').fill('12345');
  await expect(issue).toBeDisabled();
  await page.getByLabel('UPI transaction ID (UTR)').fill('412345678901');
  await issue.click();

  await expect(page).toHaveURL(/\/receipt\/JB-0001/);
  await expect(page.getByText('Transaction ref: 412345678901')).toBeVisible();
  expect(await scalar<string>('SELECT payment_method FROM orders')).toBe('upi');
  expect(await scalar<string>('SELECT upi_reference FROM orders')).toBe('412345678901');
});

test('counter staff only see the Counter tab', async ({ page }) => {
  await page.goto('/staff/counter');
  const nav = page.getByRole('navigation', { name: 'Sections' }).first();
  await expect(nav.getByRole('link', { name: 'Counter' })).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Dashboard' })).toHaveCount(0);
  await expect(nav.getByRole('link', { name: 'Settings' })).toHaveCount(0);
});
