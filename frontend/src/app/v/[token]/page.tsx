'use client';

import { useEffect, useState } from 'react';
import { AppHeader } from '../../../components/AppHeader';

type Line = { name: string; packSize: string; quantity: number; unitPrice: number };
type Receipt = {
  bookedAtIst: string;
  name: string | null;
  mobile: string | null;
  address: string | null;
  pin: string | null;
  items: Line[];
  totalAmount: number;
  paymentMode: string | null;
  channel: string;
  takenByName: string | null;
  transactionRef: string | null;
  transactionRefPending: boolean;
};

type VerifyResult = {
  status: 'genuine' | 'invalid';
  message: string;
  bookingId?: string;
  totalPackets?: number;
  receipt?: Receipt;
};

const TRANSACTION_REF_PENDING = 'Pending — will be updated shortly';

export default function VerifyPage({ params }: { params: { token: string } }) {
  const { token } = params;
  const [result, setResult] = useState<VerifyResult | null>(null);

  useEffect(() => {
    fetch(`/api/verify/${token}`)
      .then((r) => r.json())
      .then(setResult)
      .catch(() => setResult({ status: 'invalid', message: 'Invalid receipt' }));
  }, [token]);

  const r = result?.receipt;

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
                {r && (
                  <>
                    <br />
                    <strong>Booked at (IST):</strong> {r.bookedAtIst}
                    <br />
                    <strong>Channel:</strong> {r.channel} · <strong>Payment:</strong> {r.paymentMode || '—'}
                    {(r.transactionRef || r.transactionRefPending) && (
                      <>
                        <br />
                        <strong>Transaction ref:</strong> {r.transactionRef || TRANSACTION_REF_PENDING}
                      </>
                    )}
                    {r.takenByName && (
                      <>
                        <br />
                        <strong>Booked by:</strong> {r.takenByName}
                      </>
                    )}
                  </>
                )}
              </p>
            )}
            {r?.name && (
              <p>
                <strong>Name:</strong> {r.name}
                <br />
                <strong>Mobile:</strong> {r.mobile}
                <br />
                <strong>Address:</strong> {r.address} — {r.pin}
              </p>
            )}
            {r && (
              <table className="receipt-table">
                <thead>
                  <tr>
                    <th>Item</th>
                    <th>Pack</th>
                    <th>Packets</th>
                    <th>Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {r.items.map((l, i) => (
                    <tr key={i}>
                      <td>{l.name}</td>
                      <td>{l.packSize}</td>
                      <td>{l.quantity}</td>
                      <td>₹{((l.unitPrice * l.quantity) / 100).toFixed(2)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {typeof result.totalPackets === 'number' && (
              <p>
                <strong>Total packets:</strong> {result.totalPackets}
                {r && (
                  <>
                    <br />
                    <strong>Total:</strong> ₹{(r.totalAmount / 100).toFixed(2)}
                  </>
                )}
              </p>
            )}
          </div>
        )}
      </main>
    </>
  );
}
