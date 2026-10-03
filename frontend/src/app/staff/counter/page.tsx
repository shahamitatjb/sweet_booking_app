'use client';

import { useEffect, useState } from 'react';
import { formatINR } from '../../../lib/format';

type Me = { email: string; name: string; role: string };
type Item = { id: number; nameEn: string; packSize: string; pricePaise: number };

export default function CounterPage() {
  const [me, setMe] = useState<Me | null>(null);
  const [items, setItems] = useState<Item[]>([]);
  const [qty, setQty] = useState<Record<number, number>>({});
  const [form, setForm] = useState({ name: '', mobile: '', address: '', pinCode: '' });
  const [method, setMethod] = useState<'cash' | 'upi'>('cash');
  const [error, setError] = useState('');
  const [result, setResult] = useState<{ bookingId: string; amountPaise: number } | null>(null);

  useEffect(() => {
    fetch('/api/staff/me', { credentials: 'include' })
      .then((r) => r.json())
      .then((j) => setMe(j.role ? j : null))
      .catch(() => setMe(null));
    fetch('/api/admin/items')
      .then((r) => r.json())
      .then(setItems)
      .catch(() => {});
  }, []);

  async function submit() {
    setError('');
    setResult(null);
    try {
      const payload = {
        ...form,
        paymentMethod: method,
        items: Object.entries(qty)
          .filter(([, q]) => q > 0)
          .map(([itemId, quantity]) => ({ itemId: Number(itemId), quantity })),
        cashReceivedPaise: amount,
        upiReference: method === 'upi' ? 'UPI-STAFF-CONFIRMED' : undefined,
      };
      const res = await fetch('/api/staff/counter/bookings', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'Failed');
      setResult(data);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  let amount = 0;
  let packets = 0;
  for (const [id, q] of Object.entries(qty)) {
    const item = items.find((i) => i.id === Number(id));
    if (item && q > 0) {
      amount += item.pricePaise * q;
      packets += q;
    }
  }

  if (!me) {
    return (
      <main className="container">
        <div className="card">
          <h1>Not signed in</h1>
          <a className="btn" href="/staff/login">
            Staff login
          </a>
        </div>
      </main>
    );
  }

  return (
    <main className="container">
      <h1>Counter booking</h1>
      <p className="muted">
        {me.name || me.email} · {me.role}
      </p>
      {error && <div className="error">{error}</div>}
      {result && (
        <div className="card ok">
          Booking {result.bookingId} · {formatINR(result.amountPaise)}
          <div>
            <a className="btn secondary" href={`/receipt/${result.bookingId}`}>
              Open receipt
            </a>
          </div>
        </div>
      )}
      <div className="card">
        <label>Customer name</label>
        <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        <label>Mobile</label>
        <input value={form.mobile} onChange={(e) => setForm({ ...form, mobile: e.target.value })} />
        <label>Address</label>
        <textarea rows={3} value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
        <label>Pin code</label>
        <input value={form.pinCode} onChange={(e) => setForm({ ...form, pinCode: e.target.value })} />
        <label>Payment method</label>
        <select value={method} onChange={(e) => setMethod(e.target.value as 'cash' | 'upi')}>
          <option value="cash">Cash</option>
          <option value="upi">UPI (staff-confirmed)</option>
        </select>
        <h2 style={{ marginTop: 12 }}>Items</h2>
        {items.map((item) => (
          <div className="item-row" key={item.id}>
            <div>
              <strong>{item.nameEn}</strong>
              <div className="muted">
                {item.packSize} · {formatINR(item.pricePaise)}
              </div>
            </div>
            <input
              className="qty"
              type="number"
              min={0}
              value={qty[item.id] ?? 0}
              onChange={(e) => setQty((q) => ({ ...q, [item.id]: Number(e.target.value) || 0 }))}
            />
          </div>
        ))}
        <div className="total-bar">
          <div>
            <strong>{packets}</strong> packets · <strong>{formatINR(amount)}</strong> due
          </div>
          <button className="btn gold block" disabled={packets < 1 || !form.name} onClick={submit}>
            Confirm {method === 'cash' ? 'cash' : 'UPI'} & issue receipt
          </button>
        </div>
      </div>
    </main>
  );
}
