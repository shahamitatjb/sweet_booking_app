'use client';

import { useEffect, useState } from 'react';
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
  status: string;
  voidReason: string | null;
  transactionRef: string | null;
  transactionRefPending: boolean;
};

const TRANSACTION_REF_PENDING = 'Pending — will be updated shortly';

export default function ReceiptPage({ params }: { params: { id: string } }) {
  const { id } = params;
  const [data, setData] = useState<Receipt | null>(null);
  const [error, setError] = useState('');
  const [flags, setFlags] = useState({ fromCounter: false, justPaid: false });
  const [qrSrc, setQrSrc] = useState('');

  // Customers open their receipt with the private ?t= link they got after paying (or by email);
  // without it the receipt is only available to signed-in staff.
  useEffect(() => {
    const q = new URLSearchParams(window.location.search);
    setFlags({ fromCounter: q.get('from') === 'counter', justPaid: q.get('paid') === '1' });
    const token = q.get('t');
    const enc = encodeURIComponent(id);
    const base = token ? `/api/public/receipts/${enc}` : `/api/staff/receipts/${enc}`;
    const suffix = token ? `?t=${encodeURIComponent(token)}` : '';
    setQrSrc(`${base}/qr.png${suffix}`);
    fetch(`${base}${suffix}`, { credentials: 'include' })
      .then(async (r) => {
        const j = await r.json().catch(() => null);
        if (!r.ok || !j || j.error) {
          setError('Receipt not available');
          return;
        }
        setData(j);
      })
      .catch(() => setError('Receipt not available'));
  }, [id]);

  if (error) {
    return (
      <>
        <AppHeader title="Receipt" backHref="/" />
        <main className="container">
          <div className="card error">{error}</div>
          <p className="muted">Open your receipt with the link shown after payment or in your receipt email. Committee staff can open any receipt after signing in.</p>
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
        {flags.justPaid && data.transactionRefPending && (
          <div className="card no-print" role="status">
            Payment received. The bank transaction reference will be added to this receipt shortly.
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
              src={qrSrc}
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
              {(data.transactionRef || data.transactionRefPending) && (
                <>
                  <br />
                  <strong>Transaction ref:</strong> {data.transactionRef || TRANSACTION_REF_PENDING}
                </>
              )}
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
            <pre className="muted" style={{ whiteSpace: 'pre-wrap' }}>
              {data.terms}
            </pre>
            <p>{data.thankYou}</p>
          </div>
        </div>
        <div className="print-actions no-print">
          <button type="button" className="btn" onClick={() => window.print()}>
            Print
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
