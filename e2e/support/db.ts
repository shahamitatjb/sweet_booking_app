import { Client } from 'pg';
import { E2E } from './env';

/** Direct database access for resetting state and seeding fixtures between tests. */
export async function withDb<T>(fn: (db: Client) => Promise<T>): Promise<T> {
  const db = new Client(E2E.db);
  await db.connect();
  try {
    return await fn(db);
  } finally {
    await db.end();
  }
}

export type Seed = {
  ladooId: number;
  barfiId: number;
  admin: StaffSeed;
  counter: StaffSeed;
};

export type StaffSeed = { id: number; email: string; name: string; role: 'ADMIN' | 'COUNTER' };

export const DEFAULT_SETTINGS: Record<string, string> = {
  title: 'Diwali Sweets Booking',
  subtitle: 'Community Trust — Pune',
  booking_enabled: 'true',
  booking_window_open: '2000-01-01T00:00:00+05:30',
  booking_window_close: '2100-01-01T00:00:00+05:30',
  max_packets_per_item: '20',
  max_packets_total: '50',
  otp_required: 'false',
  otp_provider: 'email',
};

/** Empties every booking/staff/catalogue table, restores known settings and seeds two items and two staff. */
export async function resetAndSeed(settings: Record<string, string> = {}): Promise<Seed> {
  return withDb(async (db) => {
    await db.query(
      `TRUNCATE notification_outbox, bookings, payments, order_items, orders, otp_codes,
       cash_handovers, audit_log, staff, items, catalogue_confirmations RESTART IDENTITY CASCADE`,
    );
    await db.query(`UPDATE counters SET value = 0 WHERE name = 'booking'`);
    for (const [key, value] of Object.entries({ ...DEFAULT_SETTINGS, ...settings })) {
      await setSetting(db, key, value);
    }
    const item = async (name: string, pack: string, pricePaise: number, kg: number, sort: number) =>
      (
        await db.query(
          `INSERT INTO items (name_en, pack_size, price_paise, weight_kg, active, sort_order)
           VALUES ($1, $2, $3, $4, true, $5) RETURNING id`,
          [name, pack, pricePaise, kg, sort],
        )
      ).rows[0].id as number;
    const staff = async (email: string, name: string, role: 'ADMIN' | 'COUNTER'): Promise<StaffSeed> => {
      const id = (
        await db.query(`INSERT INTO staff (email, name, role, active) VALUES ($1, $2, $3, true) RETURNING id`, [
          email,
          name,
          role,
        ])
      ).rows[0].id as number;
      return { id: Number(id), email, name, role };
    };
    return {
      ladooId: Number(await item('Besan Ladoo', '500 g', 25000, 0.5, 1)),
      barfiId: Number(await item('Kaju Barfi', '250 g', 30000, 0.25, 2)),
      admin: await staff('admin@jb.test', 'Asha Admin', 'ADMIN'),
      counter: await staff('counter@jb.test', 'Chetan Counter', 'COUNTER'),
    };
  });
}

export async function setSetting(db: Client, key: string, value: string) {
  await db.query(
    `INSERT INTO settings (key, language, value, updated_at) VALUES ($1, 'en', $2, now())
     ON CONFLICT (key, language) DO UPDATE SET value = EXCLUDED.value, updated_at = now()`,
    [key, value],
  );
}

export async function updateSettings(settings: Record<string, string>) {
  await withDb(async (db) => {
    for (const [key, value] of Object.entries(settings)) await setSetting(db, key, value);
  });
}

export async function scalar<T = unknown>(sql: string, params: unknown[] = []): Promise<T> {
  return withDb(async (db) => {
    const r = await db.query(sql, params);
    return Object.values(r.rows[0] ?? {})[0] as T;
  });
}
