'use client';

import { useEffect, useRef, useState } from 'react';
import { api, csrfHeaders, formatINR } from '../../lib/format';
import { AdminShell } from '../../components/AdminShell';
import { useMe } from '../../components/useMe';
import { SignInRequired } from '../../components/SignInRequired';
import { Toast, useToast } from '../../components/Toast';

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
  takenBy: string;
  status: string;
  voidReason?: string;
};

type ItemSummaryRow = { name: string; packSize: string; packets: number; amountPaise: number };

type Dashboard = {
  totalBookings: number;
  totalPackets: number;
  totalWeightKg: number;
  totalCollectedPaise: number;
  byChannel?: { online: number; counterCash: number; counterUpi: number };
  itemSummary?: ItemSummaryRow[];
};

export default function AdminPage() {
  const me = useMe();
  const { toast, show } = useToast();
  const [dash, setDash] = useState<Dashboard | null>(null);
  const [rows, setRows] = useState<Booking[]>([]);
  const [q, setQ] = useState('');
  const [showVoided, setShowVoided] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [error, setError] = useState('');
  const headerCheck = useRef<HTMLInputElement>(null);

  function load() {
    api<Dashboard>('/api/admin/dashboard').then(setDash).catch((e) => setError((e as Error).message));
    api<Booking[]>(`/api/admin/bookings?q=${encodeURIComponent(q)}&voided=${showVoided ? 'true' : 'false'}`)
      .then((j) => (Array.isArray(j) ? setRows(j) : setError('Access denied')))
      .catch((e) => setError((e as Error).message));
  }

  useEffect(load, [q, showVoided]);

  const allSelected = rows.length > 0 && rows.every((r) => selected.has(r.bookingId));
  const someSelected = !allSelected && rows.some((r) => selected.has(r.bookingId));
  useEffect(() => {
    if (headerCheck.current) headerCheck.current.indeterminate = someSelected;
  }, [someSelected]);

  function toggle(id: string) {
    setSelected((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  function toggleAll() {
    setSelected(allSelected ? new Set() : new Set(rows.map((r) => r.bookingId)));
  }

  async function exportExcel() {
    try {
      const res = await fetch('/api/admin/export', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', ...csrfHeaders() },
        body: JSON.stringify({ bookingIds: Array.from(selected) }),
      });
      if (!res.ok) throw new Error('Export failed');
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'bookings.xlsx';
      a.click();
      URL.revokeObjectURL(url);
      show(`Exported ${selected.size} booking${selected.size === 1 ? '' : 's'}`);
    } catch (e) {
      show((e as Error).message, 'err');
    }
  }

  async function voidBooking(id: string) {
    const reason = window.prompt(
      `Void ${id}?\n\nNothing is deleted: the booking stays in history and is hidden from lists, totals and the receipt/QR checks.\n\nReason (optional):`,
      '',
    );
    if (reason === null) return;
    try {
      await api(`/api/admin/bookings/${id}/void`, { method: 'POST', body: JSON.stringify({ reason }) });
      show(`${id} voided`);
      setSelected((s) => {
        const n = new Set(s);
        n.delete(id);
        return n;
      });
      load();
    } catch (e) {
      show((e as Error).message, 'err');
    }
  }

  if (me === undefined) return <main className="container muted">Loading…</main>;
  if (!me) return <SignInRequired />;

  const summary = dash?.itemSummary || [];
  const totalSummaryPackets = summary.reduce((a, r) => a + Number(r.packets || 0), 0);
  const totalSummaryPaise = summary.reduce((a, r) => a + Number(r.amountPaise || 0), 0);

  return (
    <AdminShell active="admin" title="Dashboard" me={me}>
      {error && (
        <div className="card error" role="alert">
          {error}
        </div>
      )}

      {dash && (
        <>
          <div className="stat-grid">
            <div className="stat">
              <div className="lbl">Bookings</div>
              <div className="val">{dash.totalBookings}</div>
            </div>
            <div className="stat">
              <div className="lbl">Packets</div>
              <div className="val">{dash.totalPackets}</div>
            </div>
            <div className="stat">
              <div className="lbl">Weight</div>
              <div className="val">{Number(dash.totalWeightKg).toFixed(1)} kg</div>
            </div>
            <div className="stat">
              <div className="lbl">Collected</div>
              <div className="val">{formatINR(dash.totalCollectedPaise)}</div>
            </div>
          </div>
          <div className="chips" style={{ marginBottom: 12 }}>
            <span className="chip">Online {dash.byChannel?.online ?? 0}</span>
            <span className="chip">Counter cash {dash.byChannel?.counterCash ?? 0}</span>
            <span className="chip">Counter UPI {dash.byChannel?.counterUpi ?? 0}</span>
          </div>

          <section className="card">
            <h2>Packets to prepare</h2>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Item</th>
                    <th>Pack</th>
                    <th>Packets</th>
                    <th>Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {summary.map((r, i) => (
                    <tr key={i}>
                      <td>{r.name}</td>
                      <td className="muted">{r.packSize}</td>
                      <td>
                        <strong>{r.packets}</strong>
                      </td>
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
            <p className="muted small" style={{ marginTop: 8 }}>
              All time. Voided bookings excluded.
            </p>
          </section>
        </>
      )}

      <section className="card">
        <div className="section-title" style={{ marginTop: 0 }}>
          <h2>{showVoided ? 'Voided bookings' : 'Bookings'}</h2>
        </div>
        <div className="toolbar">
          <div className="search">
            <input placeholder="Search name, mobile or ID" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search bookings" />
          </div>
          {!showVoided && rows.length > 0 && (
            <button type="button" className="btn secondary sm mobile-only" onClick={toggleAll}>
              {allSelected ? 'Clear selection' : 'Select all'}
            </button>
          )}
          <button
            type="button"
            className="btn secondary sm"
            onClick={() => {
              setShowVoided((v) => !v);
              setSelected(new Set());
            }}
          >
            {showVoided ? 'Back to bookings' : 'Show voided'}
          </button>
        </div>

        <table className="data-table cards">
          <thead>
            <tr>
              <th>
                {!showVoided && (
                  <input ref={headerCheck} type="checkbox" checked={allSelected} onChange={toggleAll} aria-label="Select all bookings" />
                )}
              </th>
              <th>ID</th>
              <th>Time</th>
              <th>Name</th>
              <th>Mobile</th>
              <th>Packets</th>
              <th>Amount</th>
              <th>Mode</th>
              <th>Booked by</th>
              <th>Status</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.bookingId} className={selected.has(r.bookingId) ? 'selected' : ''}>
                <td className="check">
                  {!showVoided && (
                    <input type="checkbox" checked={selected.has(r.bookingId)} onChange={() => toggle(r.bookingId)} aria-label={`Select ${r.bookingId}`} />
                  )}
                </td>
                <td className="primary">
                  <a href={`/receipt/${r.bookingId}`}>{r.bookingId}</a>
                </td>
                <td data-label="Time" className="muted small">
                  {r.bookedAt}
                </td>
                <td data-label="Name">{r.name}</td>
                <td data-label="Mobile">{r.mobile}</td>
                <td data-label="Packets">{r.packets}</td>
                <td data-label="Amount">
                  <strong>{formatINR(r.amountPaise)}</strong>
                </td>
                <td data-label="Mode">
                  <span className={`badge ${r.channel === 'online' ? 'gold' : ''}`}>
                    {r.channel}/{r.paymentMode}
                  </span>
                </td>
                <td data-label="Booked by">{r.takenBy}</td>
                <td data-label="Status">
                  {r.status === 'voided' ? (
                    <span className="badge err" title={r.voidReason || ''}>
                      VOIDED
                    </span>
                  ) : (
                    <span className="badge ok">{r.status}</span>
                  )}
                </td>
                <td className="actions">
                  {!showVoided && r.status !== 'voided' && (
                    <button type="button" className="btn ghost sm" onClick={() => voidBooking(r.bookingId)}>
                      Void
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={11} className="muted">
                  {showVoided ? 'No voided bookings.' : 'No bookings match this search.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>

      {selected.size > 0 && !showVoided && (
        <div className="fab-bar no-print" role="toolbar" aria-label="Selection actions">
          <span>{selected.size} selected</span>
          <button type="button" className="btn gold sm" onClick={exportExcel}>
            Export Excel
          </button>
          <button type="button" className="btn ghost sm" style={{ color: '#fff' }} onClick={() => setSelected(new Set())}>
            Clear
          </button>
        </div>
      )}
      <Toast toast={toast} />
    </AdminShell>
  );
}
