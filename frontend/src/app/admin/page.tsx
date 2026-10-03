'use client';

import { useEffect, useState } from 'react';
import { formatINR } from '../../lib/format';

type Booking = {
  bookingId: string;
  bookedAt: string;
  name: string;
  mobile: string;
  address: string;
  pin: string;
  packets: number;
  amountPaise: number;
  channel: string;
  paymentMode: string;
  status: string;
};

export default function AdminPage() {
  const [dash, setDash] = useState<any>(null);
  const [rows, setRows] = useState<Booking[]>([]);
  const [q, setQ] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [error, setError] = useState('');

  function load() {
    fetch('/api/admin/dashboard', { credentials: 'include' })
      .then((r) => r.json())
      .then((j) => (j.error ? setError(j.error) : setDash(j)))
      .catch((e) => setError(String(e)));
    fetch(`/api/admin/bookings?q=${encodeURIComponent(q)}`, { credentials: 'include' })
      .then((r) => r.json())
      .then((j) => (Array.isArray(j) ? setRows(j) : setError(j.error || 'Access denied')))
      .catch((e) => setError(String(e)));
  }

  useEffect(load, [q]);

  function toggle(id: string) {
    setSelected((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  function selectAll() {
    setSelected(new Set(rows.map((r) => r.bookingId)));
  }

  async function exportExcel() {
    const res = await fetch('/api/admin/export', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ bookingIds: Array.from(selected) }),
    });
    if (!res.ok) {
      setError('Export failed — sign in as Admin');
      return;
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'bookings.xlsx';
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <main className="container" style={{ maxWidth: 960 }}>
      <h1>Admin</h1>
      <p>
        <a href="/staff/counter">Counter booking</a> · <a href="/staff/login">Login</a>
      </p>
      {error && <div className="error">{error}</div>}
      {dash && (
        <div className="card">
          <h2>Dashboard</h2>
          <div className="row">
            <div>
              <div className="muted">Bookings</div>
              <strong>{dash.totalBookings}</strong>
            </div>
            <div>
              <div className="muted">Packets</div>
              <strong>{dash.totalPackets}</strong>
            </div>
            <div>
              <div className="muted">Kg</div>
              <strong>{Number(dash.totalWeightKg).toFixed(2)}</strong>
            </div>
            <div>
              <div className="muted">Collected</div>
              <strong>{formatINR(dash.totalCollectedPaise)}</strong>
            </div>
          </div>
          <p className="muted">
            Online {dash.byChannel?.online} · Cash {dash.byChannel?.counterCash} · UPI{' '}
            {dash.byChannel?.counterUpi}
          </p>
        </div>
      )}
      <div className="card">
        <h2>Bookings</h2>
        <div className="row">
          <input placeholder="Search name, mobile, ID" value={q} onChange={(e) => setQ(e.target.value)} />
          <button className="btn secondary" onClick={selectAll}>
            Select all
          </button>
          <button className="btn secondary" onClick={() => setSelected(new Set())}>
            Clear
          </button>
          <button className="btn" onClick={exportExcel} disabled={selected.size === 0}>
            Export Excel ({selected.size})
          </button>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <table className="admin-table">
            <thead>
              <tr>
                <th></th>
                <th>ID</th>
                <th>Time</th>
                <th>Name</th>
                <th>Mobile</th>
                <th>Packets</th>
                <th>Amount</th>
                <th>Mode</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.bookingId}>
                  <td>
                    <input
                      type="checkbox"
                      checked={selected.has(r.bookingId)}
                      onChange={() => toggle(r.bookingId)}
                    />
                  </td>
                  <td>
                    <a href={`/receipt/${r.bookingId}`}>{r.bookingId}</a>
                  </td>
                  <td>{r.bookedAt}</td>
                  <td>{r.name}</td>
                  <td>{r.mobile}</td>
                  <td>{r.packets}</td>
                  <td>{formatINR(r.amountPaise)}</td>
                  <td>
                    {r.channel}/{r.paymentMode}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </main>
  );
}
