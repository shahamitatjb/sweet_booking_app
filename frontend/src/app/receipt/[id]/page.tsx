'use client';

import { useEffect, useState } from 'react';

type Line = { name: string; packSize: string; quantity: number; unitPrice: number };
type Receipt = {
  bookingId: string;
  bookedAtIst: string;
  name: string | null;
  mobile: string | null;
  address: string | null;
  pin: string | null;
  items: Line[];
  totalAmount: number;
  totalPackets: number;
  paymentMode: string | null;
  channel: string;
  terms: string;
  thankYou: string;
  title: string;
  verifyUrl: string;
  signature: string;
  status: string;
  voidReason: string | null;
};

export default function ReceiptPage({ params }: { params: { id: string } }) {
  const { id } = params;
  const [data, setData] = useState<Receipt | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    fetch(`/api/public/receipts/${id}`)
      .then((r) => r.json())
      .then((j) => {
        if (j.error) setError(j.error);
        else setData(j);
      })
      .catch((e) => setError(String(e)));
  }, [id]);

  if (error) {
    return (
      <main className="container">
        <div className="error">{error}</div>
        <p className="muted">Signed-in staff can open receipts. Customers receive PDF by email when configured.</p>
      </main>
    );
  }
  if (!data) return <main className="container">Loading…</main>;

  return (
    <main className="container">
      <div className="print-page card" style={{ border: '1px solid #000' }}>
        <h1>{data.title}</h1>
        {data.status === 'voided' && (
          <p
            style={{
              border: '2px solid var(--err)',
              color: 'var(--err)',
              fontWeight: 700,
              padding: '8px 10px',
            }}
          >
            CANCELLED{data.voidReason ? ` — ${data.voidReason}` : ''}
          </p>
        )}
        <p>
          <strong>Booking ID:</strong> {data.bookingId}
          <br />
          <strong>Booked at (IST):</strong> {data.bookedAtIst}
          <br />
          <strong>Channel:</strong> {data.channel} · <strong>Payment:</strong> {data.paymentMode || '—'}
        </p>
        {data.name && (
          <p>
            <strong>Name:</strong> {data.name}
            <br />
            <strong>Mobile:</strong> {data.mobile}
            <br />
            <strong>Address:</strong> {data.address} — {data.pin}
          </p>
        )}
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
            {data.items.map((l, i) => (
              <tr key={i}>
                <td>{l.name}</td>
                <td>{l.packSize}</td>
                <td>{l.quantity}</td>
                <td>₹{((l.unitPrice * l.quantity) / 100).toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p>
          <strong>Total packets:</strong> {data.totalPackets}
          <br />
          <strong>Total:</strong> ₹{(data.totalAmount / 100).toFixed(2)}
        </p>
        <p className="muted">
          Verify: {data.verifyUrl}
          <br />
          Signature: {data.signature}
        </p>
        <pre className="muted" style={{ whiteSpace: 'pre-wrap' }}>
          {data.terms}
        </pre>
        <p>{data.thankYou}</p>
      </div>
      <p className="no-print">
        <button className="btn" onClick={() => window.print()}>
          Print (14.9 × 21 cm)
        </button>
      </p>
    </main>
  );
}
