import { expect, test } from '@playwright/test';
import { resetAndSeed, scalar, updateSettings, type Seed } from '../support/db';
import { signIn } from '../support/auth';
import { counterCashBooking, counterUpiBooking } from '../support/api';

let seed: Seed;
test.beforeEach(async ({ context }) => {
  seed = await resetAndSeed();
  await signIn(context, seed.admin);
});

const stat = (page: import('@playwright/test').Page, label: string) =>
  page.locator('.stat').filter({ has: page.locator('.lbl', { hasText: label }) }).locator('.val');

test('dashboard shows totals and packets to prepare', async ({ page }) => {
  await counterCashBooking(seed, [
    [seed.ladooId, 2],
    [seed.barfiId, 1],
  ]);
  await counterCashBooking(seed, [[seed.ladooId, 3]], { name: 'Meera Shah', mobile: '9123456780' });

  await page.goto('/admin');
  await expect(stat(page, 'Bookings')).toHaveText('2');
  await expect(stat(page, 'Packets')).toHaveText('6');
  await expect(stat(page, 'Collected')).toHaveText('₹1,550.00');
  await expect(page.getByText('Counter cash 2')).toBeVisible();
  const ladooRow = page.getByRole('row', { name: /Besan Ladoo/ });
  await expect(ladooRow.getByRole('cell').nth(2)).toHaveText('5');
});

test('bookings can be searched', async ({ page }) => {
  await counterCashBooking(seed, [[seed.ladooId, 1]]);
  await counterCashBooking(seed, [[seed.ladooId, 1]], { name: 'Meera Shah', mobile: '9123456780' });

  await page.goto('/admin');
  const rows = page.locator('table.cards tbody tr');
  await expect(rows).toHaveCount(2);
  await page.getByLabel('Search bookings').fill('meera');
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText('Meera Shah');
  await page.getByLabel('Search bookings').fill('nobody');
  await expect(page.getByText('No bookings match this search.')).toBeVisible();
});

test('voiding a booking moves it to the voided list and updates totals', async ({ page }) => {
  await counterCashBooking(seed, [[seed.ladooId, 2]]);
  await counterCashBooking(seed, [[seed.barfiId, 1]]);
  await page.goto('/admin');
  await expect(stat(page, 'Bookings')).toHaveText('2');

  page.once('dialog', (d) => d.accept('Duplicate entry'));
  await page.getByRole('row', { name: /JB-0001/ }).getByRole('button', { name: 'Void' }).click();

  await expect(page.getByText('JB-0001 voided')).toBeVisible();
  await expect(stat(page, 'Bookings')).toHaveText('1');
  await expect(page.locator('table.cards tbody tr')).toHaveCount(1);

  await page.getByRole('button', { name: 'Show voided' }).click();
  const voided = page.getByRole('row', { name: /JB-0001/ });
  await expect(voided).toContainText('VOIDED');
  expect(await scalar<string>("SELECT void_reason FROM orders o JOIN bookings b ON b.order_id = o.id WHERE b.booking_id = 'JB-0001'"))
    .toBe('Duplicate entry');
});

test('selected bookings export to Excel', async ({ page }) => {
  await counterCashBooking(seed, [[seed.ladooId, 1]]);
  await page.goto('/admin');
  await page.getByLabel('Select JB-0001').check();
  await expect(page.getByText('1 selected')).toBeVisible();

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'Export Excel' }).click(),
  ]);
  expect(download.suggestedFilename()).toBe('bookings.xlsx');
  await expect(page.getByText('Exported 1 booking')).toBeVisible();
});

test('a treasurer reconciles a UPI booking, and admins only see the status', async ({ context, page }) => {
  await counterUpiBooking(seed, [[seed.ladooId, 1]]);
  await counterCashBooking(seed, [[seed.barfiId, 1]]);

  // Admin: sees the pending status but cannot reconcile.
  await page.goto('/admin');
  await expect(page.getByText('Unreconciled 1')).toBeVisible();
  const upiRow = page.getByRole('row', { name: /JB-0001/ });
  await expect(upiRow).toContainText('Pending');
  await expect(page.getByRole('button', { name: 'Reconcile' })).toHaveCount(0);

  await signIn(context, seed.treasurer);
  await page.goto('/admin');
  await expect(page.getByRole('row', { name: /JB-0002/ }).getByRole('button', { name: 'Reconcile' })).toHaveCount(0);
  page.once('dialog', (d) => d.accept('UTR 123456789012'));
  await upiRow.getByRole('button', { name: 'Reconcile' }).click();
  await expect(page.getByText('JB-0001 reconciled')).toBeVisible();
  await expect(upiRow).toContainText('Tara Treasurer');
  await expect(page.getByText('Unreconciled 0')).toBeVisible();
  expect(await scalar<string>("SELECT reconcile_note FROM bookings WHERE booking_id = 'JB-0001'")).toBe('UTR 123456789012');

  await page.getByRole('button', { name: 'Show unreconciled' }).click();
  await expect(page.getByText('Nothing left to reconcile.')).toBeVisible();
});

test('selected bookings can be reconciled in bulk', async ({ context, page }) => {
  await counterUpiBooking(seed, [[seed.ladooId, 1]]);
  await counterUpiBooking(seed, [[seed.barfiId, 1]]);
  await signIn(context, seed.treasurer);
  await page.goto('/admin');
  await page.getByLabel('Select all bookings').check();
  page.once('dialog', (d) => d.accept(''));
  await page.getByRole('button', { name: 'Mark reconciled' }).click();
  await expect(page.getByText('Reconciled 2')).toBeVisible();
  expect(Number(await scalar('SELECT count(*) FROM bookings WHERE reconciled_at IS NOT NULL'))).toBe(2);
});

test.describe('settings (super admin)', () => {
  test.beforeEach(async ({ context }) => {
    await signIn(context, seed.superAdmin);
  });

  test('settings changes are saved and reach the booking page', async ({ page }) => {
    await page.goto('/admin/settings');
    const pageTitle = page.getByLabel('Page title');
    await expect(pageTitle).toHaveValue('Diwali Sweets Booking');
    await pageTitle.fill('Annakut Sweets 2026');
    await expect(page.getByText('You have unsaved changes.')).toBeVisible();
    await page.getByRole('button', { name: 'Save settings' }).click();
    await expect(page.getByText('All settings saved.')).toBeVisible();

    await page.reload();
    await expect(page.getByLabel('Page title')).toHaveValue('Annakut Sweets 2026');
    await page.goto('/book');
    await expect(page).toHaveTitle('Annakut Sweets 2026');
  });

  test('a new catalogue item appears on the booking page', async ({ page }) => {
    await page.goto('/admin/settings');
    const newRow = page.getByRole('row').filter({ has: page.getByPlaceholder('New item') });
    await newRow.getByPlaceholder('New item').fill('Soan Papdi');
    await newRow.getByPlaceholder('500 g box').fill('400 g');
    await newRow.locator('input[type="number"]').first().fill('180');
    await newRow.getByRole('button', { name: 'Add' }).click();
    await expect(page.getByText('Saved Soan Papdi.')).toBeVisible();

    await page.goto('/book');
    await expect(page.getByText('Soan Papdi')).toBeVisible();
    await expect(page.getByText('₹180.00')).toBeVisible();
  });

  test('a new staff member can be added', async ({ page }) => {
    await page.goto('/admin/settings');
    const newRow = page.getByRole('row').filter({ has: page.getByPlaceholder('name@example.com') });
    await newRow.getByPlaceholder('name@example.com').fill('volunteer@jb.test');
    await newRow.getByPlaceholder('Full name').fill('Vimal Volunteer');
    await newRow.getByRole('combobox').selectOption('COUNTER');
    await newRow.getByRole('button', { name: 'Add' }).click();
    await expect(page.getByText('Saved volunteer@jb.test.')).toBeVisible();
    await expect(page.getByRole('cell', { name: 'volunteer@jb.test' })).toBeVisible();
  });

  test('delete all bookings needs bookings off and the typed phrase', async ({ page }) => {
    await counterCashBooking(seed, [[seed.ladooId, 1]]);
    await page.goto('/admin/settings');
    const del = page.getByRole('button', { name: 'Delete all bookings' });
    const phrase = page.getByLabel('Delete all bookings confirmation');

    await phrase.fill('DELETE ALL BOOKINGS');
    await expect(page.getByText('Turn "Bookings open" off and save settings first.')).toBeVisible();
    await expect(del).toBeDisabled();

    await updateSettings({ booking_enabled: 'false' });
    await page.reload();
    await expect(del).toBeDisabled();
    await phrase.fill('delete all');
    await expect(del).toBeDisabled();
    await phrase.fill('DELETE ALL BOOKINGS');
    await expect(del).toBeEnabled();

    page.once('dialog', (d) => d.accept());
    await del.click();
    await expect(page.getByText('Deleted 1 bookings and 1 orders. Next booking will be JB-0001.')).toBeVisible();
    expect(Number(await scalar('SELECT count(*) FROM bookings'))).toBe(0);
  });
});
