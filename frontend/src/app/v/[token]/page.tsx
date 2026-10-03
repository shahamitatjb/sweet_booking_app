'use client';

import { use, useEffect, useState } from 'react';

type VerifyResult = {
  status: 'genuine' | 'invalid';
  message: string;
  bookingId?: string;
  totalPackets?: number;
  warning?: string;
  scanCount?: number;
};

export default function VerifyPage({ params }: { params: Promise<{ token: string }> }) {
  const { token } = use(params);
  const [result, setResult] = useState<VerifyResult | null>(null);

  useEffect(() => {
    fetch(`/api/verify/${token}`)
      .then((r) => r.json())
      .then(setResult)
      .catch(() => setResult({ status: 'invalid', message: 'Invalid receipt' }));
  }, [token]);

  if (!result) return <main className="container">Checking…</main>;

  return (
    <main className="container">
      <div className="card">
        <h1 style={{ color: result.status === 'genuine' ? 'var(--ok)' : 'var(--err)' }}>
          {result.message}
        </h1>
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
        <p className="muted">
          Full customer details are shown only to signed-in committee staff.
        </p>
      </div>
    </main>
  );
}
