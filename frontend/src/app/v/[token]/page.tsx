'use client';

import { useEffect, useState } from 'react';
import { AppHeader } from '../../../components/AppHeader';

type VerifyResult = {
  status: 'genuine' | 'invalid';
  message: string;
  bookingId?: string;
  totalPackets?: number;
  warning?: string;
  scanCount?: number;
};

export default function VerifyPage({ params }: { params: { token: string } }) {
  const { token } = params;
  const [result, setResult] = useState<VerifyResult | null>(null);

  useEffect(() => {
    fetch(`/api/verify/${token}`)
      .then((r) => r.json())
      .then(setResult)
      .catch(() => setResult({ status: 'invalid', message: 'Invalid receipt' }));
  }, [token]);

  return (
    <>
      <AppHeader title="Receipt verification" backHref="/" />
      <main className="container">
        {!result ? (
          <p className="muted">Checking…</p>
        ) : (
          <div className={`card ${result.status === 'genuine' ? 'ok' : 'error'}`}>
            <h2 style={{ color: 'inherit' }}>{result.message}</h2>
            {result.bookingId && (
              <p>
                <strong>Booking ID:</strong> {result.bookingId}
              </p>
            )}
            {typeof result.totalPackets === 'number' && (
              <p>
                <strong>Total packets:</strong> {result.totalPackets}
              </p>
            )}
            {result.warning && <p className="error">{result.warning}</p>}
          </div>
        )}
        <p className="muted small">Full customer details are shown only to signed-in committee staff.</p>
      </main>
    </>
  );
}
