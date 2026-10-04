import { expect, test } from '@playwright/test';
import { resetAndSeed, scalar, type Seed } from '../support/db';
import { signIn } from '../support/auth';
import { CUSTOMER } from '../support/api';

let seed: Seed;
test.beforeEach(async ({ context, page }) => {
  seed = await resetAndSeed();
  await signIn(context, seed.counter);
  // The counter flow opens the print dialog; record it instead of blocking the browser.
  await page.addInitScript(() => {
    (window as any).__printed = 0;
    window.print = () => {
      (window as any).__printed++;
    };
  });
});

test('counter staff issue a cash booking and the receipt is printed', async ({ page }) => {
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

  await expect(page).toHaveURL(/\/receipt\/JB-0001\?print=1&from=counter$/);
  await expect(page.locator('.done-banner')).toContainText('JB-0001');
  await expect(page.getByText('Booked by: Chetan Counter')).toBeVisible();
  await expect.poll(() => page.evaluate(() => (window as any).__printed)).toBe(1);
  expect(await scalar<string>('SELECT payment_method FROM orders')).toBe('cash');
});

test('UPI is recorded when chosen', async ({ page }) => {
  await page.goto('/staff/counter');
  await page.getByRole('radio', { name: /UPI/ }).click();
  await page.getByLabel('Customer name').fill(CUSTOMER.name);
  await page.getByLabel('Mobile').fill(CUSTOMER.mobile);
  await page.getByLabel('Address').fill(CUSTOMER.address);
  await page.getByLabel('Pin code').fill(CUSTOMER.pinCode);
  await page.getByRole('button', { name: 'Add one Kaju Barfi' }).click();
  await page.getByRole('button', { name: /Confirm UPI & issue receipt/ }).click();

  await expect(page).toHaveURL(/\/receipt\/JB-0001/);
  expect(await scalar<string>('SELECT payment_method FROM orders')).toBe('upi');
});

test('counter staff only see the Counter tab', async ({ page }) => {
  await page.goto('/staff/counter');
  const nav = page.getByRole('navigation', { name: 'Sections' }).first();
  await expect(nav.getByRole('link', { name: 'Counter' })).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Dashboard' })).toHaveCount(0);
  await expect(nav.getByRole('link', { name: 'Settings' })).toHaveCount(0);
});
