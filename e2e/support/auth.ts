import { createHmac } from 'node:crypto';
import type { BrowserContext } from '@playwright/test';
import { E2E } from './env';
import type { StaffSeed } from './db';

const b64url = (input: Buffer | string) => Buffer.from(input).toString('base64url');

/**
 * Signs the same HS256 session token the API issues after Google sign-in (JwtService.issue),
 * using the test JWT secret the API was started with. No production login shortcut needed.
 */
export function staffToken(staff: StaffSeed, ttlMinutes = 60): string {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64url(
    JSON.stringify({
      sid: staff.id,
      email: staff.email,
      role: staff.role,
      name: staff.name,
      sub: staff.email,
      iat: now,
      exp: now + ttlMinutes * 60,
    }),
  );
  const signature = createHmac('sha256', E2E.jwtSecret).update(`${header}.${payload}`).digest('base64url');
  return `${header}.${payload}.${signature}`;
}

/** Puts the staff session cookie in the browser, as the Google callback would. */
export async function signIn(context: BrowserContext, staff: StaffSeed) {
  await context.addCookies([
    { name: 'jb_token', value: staffToken(staff), url: E2E.frontendUrl, httpOnly: true, sameSite: 'Lax' },
  ]);
}
