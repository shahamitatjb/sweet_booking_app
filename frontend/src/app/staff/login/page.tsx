'use client';

import { useEffect, useState } from 'react';
import { AppHeader } from '../../../components/AppHeader';

const MESSAGES: Record<string, { title: string; body: string }> = {
  denied: {
    title: 'Not on the staff allowlist',
    body: 'Your Google account signed in, but it has no active row in the staff table. Ask an admin to add it, then try again.',
  },
  failed: {
    title: 'Sign-in failed',
    body: 'Google accepted the login but the API could not complete it. Check the API log for the [AUTH] lines.',
  },
  oauth: {
    title: 'Google sign-in failed',
    body: 'Google or the API rejected the OAuth exchange. Check the API log for the [AUTH] lines — common causes are a wrong GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET or a redirect URI that is not registered in Google Console.',
  },
};

export default function StaffLogin() {
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setError(new URLSearchParams(window.location.search).get('error'));
  }, []);

  const message = error ? MESSAGES[error] : undefined;

  return (
    <>
      <AppHeader title="Committee sign in" subtitle="Counter and admin access" backHref="/" />
      <main className="container">
        <div className="card">
          <p className="muted">Google sign-in for allowlisted committee emails only. Your Google name is shown on counter receipts.</p>
          {message && (
            <div role="alert" className="card error flat">
              <strong style={{ display: 'block', marginBottom: 4 }}>{message.title}</strong>
              <span className="small">{message.body}</span>
            </div>
          )}
          {error && !message && (
            <div role="alert" className="card error flat">
              <strong>Sign-in error</strong>
              <span className="small"> (code: {error}) — see the API log for details.</span>
            </div>
          )}
          <a className="btn block lg" href="/oauth2/authorization/google">
            Continue with Google
          </a>
        </div>
      </main>
    </>
  );
}
