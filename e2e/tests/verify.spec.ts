import { expect, test } from '@playwright/test';
import { resetAndSeed, type Seed } from '../support/db';
import { counterCashBooking, qrToken, staffPost } from '../support/api';
import { signIn } from '../support/auth';

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

test("the customer's private link shows their receipt and QR code", async ({ page }) => {
  const id = await counterCashBooking(seed, [
    [seed.ladooId, 2],
    [seed.barfiId, 1],
  ]);
  const token = (await qrToken(id)).split('.')[1];

  await page.goto(`/receipt/${id}?t=${token}`);
  await expect(page.getByText(`Booking ID: ${id}`)).toBeVisible();
  await expect(page.getByText('Name: Ravi Kumar')).toBeVisible();
  await expect(page.getByText('Booked by: Chetan Counter')).toBeVisible();
  await expect(page.locator('.receipt-table tbody tr')).toHaveCount(2);
  await expect(page.getByText('Total: ₹800.00')).toBeVisible();
  const qr = page.getByAltText('Scan to verify this receipt');
  await expect(qr).toBeVisible();
  await expect.poll(() => qr.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
});

test('a guessed booking ID shows nothing about the customer', async ({ page }) => {
  const id = await counterCashBooking(seed, [[seed.ladooId, 1]]);

  for (const url of [`/receipt/${id}`, `/receipt/${id}?t=guess`]) {
    await page.goto(url);
    await expect(page.getByText('Receipt not available')).toBeVisible();
    await expect(page.getByText('Ravi Kumar')).toHaveCount(0);
    await expect(page.getByText('9876543210')).toHaveCount(0);
  }
});

test('signed-in staff open any receipt without the link', async ({ context, page }) => {
  const id = await counterCashBooking(seed, [[seed.ladooId, 1]]);
  await signIn(context, seed.admin);

  await page.goto(`/receipt/${id}`);
  await expect(page.getByText('Name: Ravi Kumar')).toBeVisible();
  const qr = page.getByAltText('Scan to verify this receipt');
  await expect.poll(() => qr.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);
});
