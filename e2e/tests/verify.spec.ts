import { expect, test } from '@playwright/test';
import { resetAndSeed, type Seed } from '../support/db';
import { counterCashBooking, qrToken, staffPost } from '../support/api';

let seed: Seed;
test.beforeEach(async () => {
  seed = await resetAndSeed();
});

test('scanning a receipt QR shows genuine, then warns on a repeat scan', async ({ page }) => {
  const id = await counterCashBooking(seed, [[seed.ladooId, 3]]);
  const token = await qrToken(id);

  await page.goto(`/v/${token}`);
  await expect(page.getByRole('heading', { name: 'Genuine, paid' })).toBeVisible();
  await expect(page.getByText(`Booking ID: ${id}`)).toBeVisible();
  await expect(page.getByText('Total packets: 3')).toBeVisible();

  await page.reload();
  await expect(page.getByText('This receipt has been scanned before')).toBeVisible();
});

test('forged and cancelled receipts are rejected', async ({ page }) => {
  const id = await counterCashBooking(seed, [[seed.ladooId, 1]]);

  await page.goto(`/v/${id}.forged`);
  await expect(page.getByRole('heading', { name: 'Invalid receipt' })).toBeVisible();

  const token = await qrToken(id);
  await staffPost(seed.admin, `/api/admin/bookings/${id}/void`, { reason: 'Test' });
  await page.goto(`/v/${token}`);
  await expect(page.getByRole('heading', { name: 'Booking cancelled' })).toBeVisible();
});

test('the receipt page shows the booking and its QR code', async ({ page }) => {
  const id = await counterCashBooking(seed, [
    [seed.ladooId, 2],
    [seed.barfiId, 1],
  ]);

  await page.goto(`/receipt/${id}`);
  await expect(page.getByText(`Booking ID: ${id}`)).toBeVisible();
  await expect(page.getByText('Booked by: Chetan Counter')).toBeVisible();
  await expect(page.locator('.receipt-table tbody tr')).toHaveCount(2);
  await expect(page.getByText('Total: ₹800.00')).toBeVisible();
  const qr = page.getByAltText('Scan to verify this receipt');
  await expect(qr).toBeVisible();
  expect(await qr.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
});
