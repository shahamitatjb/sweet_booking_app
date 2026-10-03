'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';

const MESSAGES: Record<string, { title: string; body: string; tone: 'error' | 'muted' }> = {
  denied: {
    title: 'Not on the staff allowlist',
    body: 'Your Google account signed in, but it has no active row in the staff table. Ask an admin to add it, then try again.',
    tone: 'error',
  },
  failed: {
    title: 'Sign-in failed',
    body: 'Google accepted the login but the API could not complete it. Check the API log for the [AUTH] lines.',
    tone: 'error',
  },
  oauth: {
    title: 'Google sign-in failed',
    body: 'Google or the API rejected the OAuth exchange. Check the API log for the [AUTH] lines — common causes are a wrong GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET or a redirect URI that is not registered in Google Console.',
    tone: 'error',
  },
};

export default function StaffLogin() {
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    setError(params.get('error'));
  }, []);

  const message = error ? MESSAGES[error] : undefined;

  return (
    <main className="container">
      <Link className="back-link" href="/">
        ← Back to home
      </Link>
      <div className="card">
        <h1>Staff login</h1>
        <p className="muted">Google sign-in for allowlisted committee emails only.</p>
        {message && (
          <div
            role="alert"
            className="card"
            style={{ border: '1px solid #c62828', background: '#fdecea', marginBottom: 12 }}
          >
            <strong style={{ display: 'block', marginBottom: 4 }}>{message.title}</strong>
            <span className="muted">{message.body}</span>
          </div>
        )}
        {error && !message && (
          <div role="alert" className="card" style={{ border: '1px solid #c62828', background: '#fdecea', marginBottom: 12 }}>
            <strong>Sign-in error</strong>
            <span className="muted"> (code: {error}) — see the API log for details.</span>
          </div>
        )}
        <a className="btn block" href="/oauth2/authorization/google">
          Continue with Google
        </a>
      </div>
    </main>
  );
}
