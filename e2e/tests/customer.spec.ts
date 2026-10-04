import { expect, test, type Page } from '@playwright/test';
import { resetAndSeed, scalar, updateSettings, type Seed } from '../support/db';
import { CUSTOMER } from '../support/api';
import { hasRazorpayKeys } from '../support/env';

let seed: Seed;
test.beforeEach(async () => {
  seed = await resetAndSeed();
});

const add = (page: Page, item: string) => page.getByRole('button', { name: `Add one ${item}` });
const next = (page: Page) => page.getByRole('button', { name: 'Next' });

async function fillDetails(page: Page, c: Partial<typeof CUSTOMER> = {}) {
  const v = { ...CUSTOMER, ...c };
  await page.getByLabel('Full name').fill(v.name);
  await page.getByLabel('Mobile number').fill(v.mobile);
  await page.getByLabel(/^Email/).fill(v.email);
  await page.getByLabel('Full address').fill(v.address);
  await page.getByLabel('Pin code').fill(v.pinCode);
}

test('home page leads to booking', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Diwali Sweets Booking' }).first()).toBeVisible();
  await page.getByRole('link', { name: 'Start booking' }).click();
  await expect(page).toHaveURL(/\/book$/);
  await expect(page.getByRole('heading', { name: 'Choose your sweets' })).toBeVisible();
  await expect(page.getByText('Besan Ladoo')).toBeVisible();
  await expect(page.getByText('Kaju Barfi')).toBeVisible();
});

test('closed booking shows a notice and blocks the first step', async ({ page }) => {
  await updateSettings({ booking_enabled: 'false' });
  await page.goto('/');
  await expect(page.getByText('Online booking is currently closed').first()).toBeVisible();

  await page.goto('/book');
  await expect(page.getByText('Online booking is currently closed').first()).toBeVisible();
  await add(page, 'Besan Ladoo').click();
  await expect(next(page)).toBeDisabled();
});

test('cart totals update and packet limits are enforced', async ({ page }) => {
  await updateSettings({ max_packets_per_item: '3', max_packets_total: '4' });
  await page.goto('/book');

  await expect(next(page)).toBeDisabled(); // empty cart
  for (let i = 0; i < 3; i++) await add(page, 'Besan Ladoo').click();
  await expect(add(page, 'Besan Ladoo')).toBeDisabled(); // per-item cap
  await expect(page.getByLabel('Besan Ladoo packets')).toHaveValue('3');
  await expect(page.locator('.bottom-bar .amt')).toHaveText('₹750.00');

  await add(page, 'Kaju Barfi').click();
  await expect(next(page)).toBeEnabled();
  await add(page, 'Kaju Barfi').click(); // 5 packets > total cap of 4
  await expect(page.getByText('Too many packets in one booking')).toBeVisible();
  await expect(next(page)).toBeDisabled();

  await page.getByRole('button', { name: 'Remove one Kaju Barfi' }).click();
  await expect(next(page)).toBeEnabled();
  await expect(page.locator('.bottom-bar .amt')).toHaveText('₹1,050.00');
});

test('customer details are validated field by field', async ({ page }) => {
  await page.goto('/book');
  await add(page, 'Besan Ladoo').click();
  await next(page).click();

  await page.getByLabel('Full name').fill('Al');
  await page.getByLabel('Full name').blur();
  await expect(page.getByText('Enter at least 3 characters')).toBeVisible();
  await page.getByLabel('Mobile number').fill('12345');
  await page.getByLabel('Mobile number').blur();
  await expect(page.getByText('Enter a 10-digit mobile number starting with 6-9')).toBeVisible();
  await page.getByLabel('Pin code').fill('411');
  await page.getByLabel('Pin code').blur();
  await expect(page.getByText('Enter a 6-digit pin code')).toBeVisible();
  await expect(next(page)).toBeDisabled();

  await fillDetails(page);
  await expect(page.getByText('Enter at least 3 characters')).toBeHidden();
  await expect(next(page)).toBeEnabled();
});

test('review step summarises the order and requires accepting the terms', async ({ page }) => {
  await page.goto('/book');
  await add(page, 'Besan Ladoo').click();
  await add(page, 'Besan Ladoo').click();
  await add(page, 'Kaju Barfi').click();
  await next(page).click();
  await fillDetails(page);
  await next(page).click();

  await expect(page.getByRole('heading', { name: 'Your order' })).toBeVisible();
  await expect(page.locator('.summary-list li')).toHaveCount(2);
  await expect(page.locator('.summary-total')).toContainText('3 packets');
  await expect(page.locator('.summary-total .amt')).toHaveText('₹800.00');
  await expect(page.getByText(CUSTOMER.address, { exact: false })).toBeVisible();

  const pay = page.getByRole('button', { name: /Pay now/ });
  await expect(pay).toBeDisabled();
  await page.getByLabel('I accept the terms and conditions').check();
  await expect(pay).toBeEnabled();
  await expect(pay).toHaveText('Pay now · ₹800.00');

  // Edit goes back to the cart with quantities kept.
  await page.getByRole('button', { name: 'Edit' }).first().click();
  await expect(page.getByLabel('Besan Ladoo packets')).toHaveValue('2');
});

test('OTP must be requested and entered before review when switched on', async ({ page }) => {
  await updateSettings({ otp_required: 'true', otp_provider: 'email' });
  await page.goto('/book');
  await add(page, 'Besan Ladoo').click();
  await next(page).click();
  // OTP rate limits live in the API's memory and outlive the DB reset, so use a fresh address.
  await fillDetails(page, { email: `otp-${Date.now()}@example.com` });

  await expect(next(page)).toBeDisabled();
  await page.getByRole('button', { name: 'Send OTP' }).click();
  await expect(page.getByText(`OTP sent to`)).toBeVisible();
  const devCode = (await page.getByText(/Dev OTP: \d{6}/).textContent())!.replace(/\D/g, '');
  await page.getByLabel('OTP', { exact: true }).fill(devCode);
  await expect(next(page)).toBeEnabled();
});

test.describe('real Razorpay test-mode payment', () => {
  test.skip(!hasRazorpayKeys(), 'Set RAZORPAY_TEST_KEY_ID / RAZORPAY_TEST_KEY_SECRET (rzp_test_ keys) to run');

  test('a customer pays by card and lands on their receipt', async ({ page }) => {
    test.setTimeout(180_000);
    await page.goto('/book');
    await add(page, 'Besan Ladoo').click();
    await next(page).click();
    await fillDetails(page);
    await next(page).click();
    await page.getByLabel('I accept the terms and conditions').check();
    await page.getByRole('button', { name: /Pay now/ }).click();

    // Razorpay Standard Checkout renders in an iframe; test mode accepts its test card.
    const checkout = page.frameLocator('iframe.razorpay-checkout-frame');
    await checkout.getByText(/Cards?/).first().click();
    await checkout.locator('input[name="card.number"]').fill('4111 1111 1111 1111');
    await checkout.locator('input[name="card.expiry"]').fill('12 / 30');
    await checkout.locator('input[name="card.cvv"]').fill('123');
    await checkout.getByRole('button', { name: /Pay|Continue/ }).first().click();

    // Test-mode bank page opens in a popup: choose Success.
    const bank = await page.waitForEvent('popup', { timeout: 60_000 });
    await bank.getByRole('button', { name: /Success/i }).click();

    await expect(page).toHaveURL(/\/receipt\/JB-0001\?t=[\w-]+$/, { timeout: 90_000 });
    await expect(page.getByText('Booking ID:')).toBeVisible();
    expect(await scalar<string>("SELECT status FROM orders WHERE channel = 'online'")).toBe('paid');
  });
});
