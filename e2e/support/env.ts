/**
 * One place for the settings the stack is started with (playwright.config.ts) and the
 * tests rely on (support/*). Defaults suit a local run; CI overrides via env.
 */
export const E2E = {
  frontendUrl: process.env.E2E_FRONTEND_URL || 'http://localhost:3000',
  apiUrl: process.env.E2E_API_URL || 'http://localhost:8080',
  db: {
    host: process.env.E2E_DB_HOST || 'localhost',
    port: Number(process.env.E2E_DB_PORT || 5433),
    database: process.env.E2E_DB_NAME || 'jb_e2e',
    user: process.env.E2E_DB_USER || 'jb',
    password: process.env.E2E_DB_PASSWORD || 'jb',
  },
  // Test-only secrets: the API is started with these, and the tests sign staff tokens with them.
  jwtSecret: 'e2e-jwt-secret-not-for-production-0123456789',
  qrSecret: 'e2e-qr-secret-not-for-production-012345',
  // Razorpay TEST-mode keys. The real-payment test is skipped when they are absent.
  razorpayKeyId: process.env.RAZORPAY_TEST_KEY_ID || '',
  razorpayKeySecret: process.env.RAZORPAY_TEST_KEY_SECRET || '',
};

export const hasRazorpayKeys = () => E2E.razorpayKeyId.startsWith('rzp_test_') && !!E2E.razorpayKeySecret;
