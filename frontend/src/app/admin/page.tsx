'use client';

import { useEffect, useState } from 'react';
import { csrfHeaders, formatINR } from '../../lib/format';

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
  voidReason?: string;
};

type ItemSummaryRow = {
  name: string;
  packSize: string;
  packets: number;
  amountPaise: number;
};

export default function AdminPage() {
  const [dash, setDash] = useState<any>(null);
  const [rows, setRows] = useState<Booking[]>([]);
  const [q, setQ] = useState('');
  const [showVoided, setShowVoided] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  function load() {
    fetch('/api/admin/dashboard', { credentials: 'include' })
      .then((r) => r.json())
      .then((j) => (j.error ? setError(j.error) : setDash(j)))
      .catch((e) => setError(String(e)));
    fetch(
      `/api/admin/bookings?q=${encodeURIComponent(q)}&voided=${showVoided ? 'true' : 'false'}`,
      { credentials: 'include' },
    )
      .then((r) => r.json())
      .then((j) => (Array.isArray(j) ? setRows(j) : setError(j.error || 'Access denied')))
      .catch((e) => setError(String(e)));
  }

  useEffect(load, [q, showVoided]);

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
      headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
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

  async function voidBooking(id: string) {
    const reason = window.prompt(`Void ${id}?\n\nNothing is deleted: the booking stays in history and is hidden from lists, totals and the receipt/QR checks.\n\nReason (optional):`, '');
    if (reason === null) return;
    setError('');
    setNotice('');
    try {
      const res = await fetch(`/api/admin/bookings/${id}/void`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
        body: JSON.stringify({ reason }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || 'Void failed');
      setNotice(`${id} voided`);
      setSelected((s) => {
        const n = new Set(s);
        n.delete(id);
        return n;
      });
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  const summary: ItemSummaryRow[] = dash?.itemSummary || [];
  const totalSummaryPackets = summary.reduce((a, r) => a + Number(r.packets || 0), 0);
  const totalSummaryPaise = summary.reduce((a, r) => a + Number(r.amountPaise || 0), 0);

  return (
    <main className="container" style={{ maxWidth: 960 }}>
      <h1>Admin</h1>
      <p>
        <a href="/staff/counter">Counter booking</a> ·{' '}
        <a href="/admin/settings">Settings</a> · <a href="/staff/login">Login</a>
      </p>
      {error && <div className="error">{error}</div>}
      {notice && <div className="card" style={{ borderColor: 'var(--ok)' }}>{notice}</div>}
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
          <div style={{ overflowX: 'auto' }}>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>Item</th>
                  <th>Pack</th>
                  <th>Packets booked</th>
                  <th>Amount collected</th>
                </tr>
              </thead>
              <tbody>
                {summary.map((r, i) => (
                  <tr key={i}>
                    <td>{r.name}</td>
                    <td>{r.packSize}</td>
                    <td>{r.packets}</td>
                    <td>{formatINR(r.amountPaise)}</td>
                  </tr>
                ))}
                <tr>
                  <td>
                    <strong>Total</strong>
                  </td>
                  <td></td>
                  <td>
                    <strong>{totalSummaryPackets}</strong>
                  </td>
                  <td>
                    <strong>{formatINR(totalSummaryPaise)}</strong>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p className="muted">All time. Voided bookings excluded.</p>
        </div>
      )}
      <div className="card">
        <h2>{showVoided ? 'Voided bookings' : 'Bookings'}</h2>
        <div className="row">
          <input placeholder="Search name, mobile, ID" value={q} onChange={(e) => setQ(e.target.value)} />
          <button className="btn secondary" onClick={selectAll}>
            Select all
          </button>
          <button className="btn secondary" onClick={() => setSelected(new Set())}>
            Clear
          </button>
          <button
            className="btn secondary"
            onClick={() => {
              setShowVoided((v) => !v);
              setSelected(new Set());
            }}
          >
            {showVoided ? 'Back to bookings' : 'Show voided'}
          </button>
          <button
            className="btn"
            onClick={exportExcel}
            disabled={selected.size === 0 || showVoided}
            title={showVoided ? 'Voided bookings are not exported' : undefined}
          >
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
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.bookingId}>
                  <td>
                    {!showVoided && (
                      <input
                        type="checkbox"
                        checked={selected.has(r.bookingId)}
                        onChange={() => toggle(r.bookingId)}
                      />
                    )}
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
                  <td>
                    {r.status === 'voided' ? (
                      <span title={r.voidReason || ''} style={{ color: 'var(--err)', fontWeight: 700 }}>
                        VOIDED
                      </span>
                    ) : (
                      r.status
                    )}
                  </td>
                  <td>
                    {!showVoided && r.status !== 'voided' && (
                      <button className="btn secondary" onClick={() => voidBooking(r.bookingId)}>
                        Void
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={10} className="muted">
                    {showVoided ? 'No voided bookings.' : 'No bookings match this search.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </main>
  );
}
