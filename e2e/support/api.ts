import { request } from '@playwright/test';
import { E2E } from './env';
import { staffToken } from './auth';
import type { Seed, StaffSeed } from './db';

const CSRF = 'e2e-csrf';

/** Calls the API directly (not through the UI) to set up bookings quickly. */
export async function staffPost<T = any>(staff: StaffSeed, path: string, body: unknown): Promise<T> {
  const ctx = await request.newContext({ baseURL: E2E.apiUrl });
  try {
    const res = await ctx.post(path, {
      data: body,
      headers: {
        Authorization: `Bearer ${staffToken(staff)}`,
        'X-XSRF-TOKEN': CSRF,
        Cookie: `XSRF-TOKEN=${CSRF}`,
      },
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok()) throw new Error(`${path} -> ${res.status()}: ${JSON.stringify(json)}`);
    return json as T;
  } finally {
    await ctx.dispose();
  }
}

export const CUSTOMER = {
  name: 'Ravi Kumar',
  mobile: '9876543210',
  address: '12 Shivaji Nagar, Pune',
  pinCode: '411005',
  email: 'ravi@example.com',
};

/** Issues a counter cash booking through the API and returns its booking ID. */
export async function counterCashBooking(
  seed: Seed,
  items: Array<[number, number]>,
  customer: Partial<typeof CUSTOMER> = {},
): Promise<string> {
  const res = await staffPost<{ bookingId: string }>(seed.counter, '/api/staff/counter/bookings', {
    ...CUSTOMER,
    ...customer,
    paymentMethod: 'cash',
    items: items.map(([itemId, quantity]) => ({ itemId, quantity })),
  });
  return res.bookingId;
}

/** Issues a counter UPI booking through the API and returns its booking ID. */
export async function counterUpiBooking(seed: Seed, items: Array<[number, number]>): Promise<string> {
  const res = await staffPost<{ bookingId: string }>(seed.counter, '/api/staff/counter/bookings', {
    ...CUSTOMER,
    paymentMethod: 'upi',
    upiReference: '123456789012',
    items: items.map(([itemId, quantity]) => ({ itemId, quantity })),
  });
  return res.bookingId;
}

/** The signed token a receipt's QR code points to (/v/<token>). */
export async function qrToken(bookingId: string): Promise<string> {
  const { scalar } = await import('./db');
  const sig = await scalar<string>('SELECT qr_signature FROM bookings WHERE booking_id = $1', [bookingId]);
  return `${bookingId}.${sig}`;
}
