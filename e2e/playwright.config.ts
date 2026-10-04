import { defineConfig, devices } from '@playwright/test';
import { E2E } from './support/env';

/**
 * Starts the real API (built jar) and the real frontend (`next start`, built beforehand),
 * pointed at a disposable Postgres (see run-local.sh / the CI workflow). Tests share that
 * database and reset it in beforeEach, so they run one at a time.
 */
const db = E2E.db;

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: E2E.frontendUrl,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    // Customers mostly book on phones: the public pages run again at phone size.
    { name: 'phone', use: { ...devices['Pixel 7'] }, testMatch: /(customer|verify)\.spec\.ts/ },
  ],
  webServer: [
    {
      command: 'java -jar ../backend/target/jb-api-0.1.0.jar',
      url: `${E2E.apiUrl}/api/health`,
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'ignore',
      stderr: 'pipe',
      env: {
        SPRING_PROFILES_ACTIVE: 'e2e',
        PORT: new URL(E2E.apiUrl).port,
        DATABASE_URL: `jdbc:postgresql://${db.host}:${db.port}/${db.database}`,
        DATABASE_USERNAME: db.user,
        DATABASE_PASSWORD: db.password,
        FRONTEND_ORIGIN: E2E.frontendUrl,
        JWT_SECRET: E2E.jwtSecret,
        QR_HMAC_SECRET: E2E.qrSecret,
        OTP_PROVIDER: 'dev',
        EMAIL_PROVIDER: 'console',
        RAZORPAY_MODE: 'test',
        RAZORPAY_KEY_ID: E2E.razorpayKeyId,
        RAZORPAY_KEY_SECRET: E2E.razorpayKeySecret,
        RAZORPAY_WEBHOOK_SECRET: '',
        BOOTSTRAP_ADMIN_EMAILS: '',
        BOOKING_ENABLED_OVERRIDE: '',
      },
    },
    {
      command: 'npm --prefix ../frontend run start',
      url: E2E.frontendUrl,
      timeout: 120_000,
      reuseExistingServer: !process.env.CI,
      stdout: 'ignore',
      stderr: 'pipe',
      env: { PORT: new URL(E2E.frontendUrl).port, API_PROXY_TARGET: E2E.apiUrl },
    },
  ],
});
