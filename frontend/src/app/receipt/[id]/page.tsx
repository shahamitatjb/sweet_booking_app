'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { AppHeader } from '../../../components/AppHeader';

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
  takenByName: string | null;
  terms: string;
  thankYou: string;
  title: string;
  verifyUrl: string;
  signature: string;
  status: string;
  voidReason: string | null;
};

const PRINT_DELAY_MS = 400;

export default function ReceiptPage({ params }: { params: { id: string } }) {
  const { id } = params;
  const [data, setData] = useState<Receipt | null>(null);
  const [error, setError] = useState('');
  const [flags, setFlags] = useState({ autoPrint: false, fromCounter: false });
  const printed = useRef(false);

  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    setFlags({ autoPrint: q.get('print') === '1', fromCounter: q.get('from') === 'counter' });
  }, []);

  useEffect(() => {
    fetch(`/api/public/receipts/${id}`)
      .then((r) => r.json())
      .then((j) => (j.error ? setError(j.error) : setData(j)))
      .catch((e) => setError(String(e)));
  }, [id]);

  // Counter flow lands here with ?print=1: open the print dialog once the receipt has rendered.
  useEffect(() => {
    if (!data || !flags.autoPrint || printed.current) return;
    printed.current = true;
    const timer = window.setTimeout(() => window.print(), PRINT_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [data, flags.autoPrint]);

  if (error) {
    return (
      <>
        <AppHeader title="Receipt" backHref="/" />
        <main className="container">
          <div className="card error">{error}</div>
          <p className="muted">Signed-in staff can open receipts. Customers receive the PDF by email when configured.</p>
        </main>
      </>
    );
  }
  if (!data) return <main className="container muted">Loading…</main>;

  const isVoided = data.status === 'voided';

  return (
    <>
      <AppHeader title={data.title} subtitle={`Receipt ${data.bookingId}`} backHref={flags.fromCounter ? '/staff/counter' : '/'} />
      <main className="container">
        {flags.fromCounter && !isVoided && (
          <div className="card ok no-print done-banner">
            Booking issued
            <div className="id">{data.bookingId}</div>
          </div>
        )}
        <div className="receipt-frame">
          <div className="print-page">
            <h1>{data.title}</h1>
            {isVoided && (
              <p style={{ border: '2px solid var(--err)', color: 'var(--err)', fontWeight: 700, padding: '8px 10px' }}>
                CANCELLED{data.voidReason ? ` — ${data.voidReason}` : ''}
              </p>
            )}
            <img
              className="qr-box"
              src={`/api/public/receipts/${data.bookingId}/qr.png`}
              alt="Scan to verify this receipt"
              width={113}
              height={113}
            />
            <p>
              <strong>Booking ID:</strong> {data.bookingId}
              <br />
              <strong>Booked at (IST):</strong> {data.bookedAtIst}
              <br />
              <strong>Channel:</strong> {data.channel} · <strong>Payment:</strong> {data.paymentMode || '—'}
              {data.takenByName && (
                <>
                  <br />
                  <strong>Booked by:</strong> {data.takenByName}
                </>
              )}
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
        </div>
        <div className="print-actions no-print">
          <button type="button" className="btn" onClick={() => window.print()}>
            Print (14.9 × 21 cm)
          </button>
          {flags.fromCounter ? (
            <Link className="btn gold" href="/staff/counter">
              New booking
            </Link>
          ) : (
            <Link className="btn secondary" href="/">
              Home
            </Link>
          )}
        </div>
      </main>
    </>
  );
}
