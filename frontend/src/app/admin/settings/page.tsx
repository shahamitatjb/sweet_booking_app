'use client';

import { useEffect, useState } from 'react';
import { api } from '../../../lib/format';
import { AdminShell } from '../../../components/AdminShell';
import { ROLES, canConfigure } from '../../../lib/roles';
import { useMe } from '../../../components/useMe';
import { SignInRequired } from '../../../components/SignInRequired';
import { Toast, useToast } from '../../../components/Toast';

type Field = {
  key: string;
  label: string;
  type: 'bool' | 'number' | 'text' | 'textarea' | 'datetime' | 'select';
  hint?: string;
  options?: string[];
  group: 'window' | 'limits' | 'verification' | 'texts';
};

const FIELDS: Field[] = [
  { key: 'booking_enabled', label: 'Bookings open', type: 'bool', group: 'window', hint: 'Turn off to stop new bookings without deploying.' },
  { key: 'booking_window_open', label: 'Window opens', type: 'datetime', group: 'window', hint: 'IST. Leave blank to open immediately.' },
  { key: 'booking_window_close', label: 'Window closes', type: 'datetime', group: 'window', hint: 'IST. Leave blank to stay open.' },
  { key: 'max_packets_per_item', label: 'Max packets per item', type: 'number', group: 'limits' },
  { key: 'max_packets_total', label: 'Max packets per booking', type: 'number', group: 'limits' },
  { key: 'otp_required', label: 'Require OTP verification', type: 'bool', group: 'verification', hint: 'Off by default. When on, customers must verify a one-time code before paying.' },
  { key: 'otp_provider', label: 'OTP channel', type: 'select', options: ['email', 'sms'], group: 'verification', hint: 'sms sends to the mobile number once an SMS gateway is wired up; email sends to the email address and makes it a required field.' },
  { key: 'title', label: 'Page title', type: 'text', group: 'texts' },
  { key: 'subtitle', label: 'Subtitle', type: 'text', group: 'texts' },
  { key: 'notice', label: 'Notice on booking page', type: 'textarea', group: 'texts' },
  { key: 'terms', label: 'Terms', type: 'textarea', group: 'texts' },
  { key: 'thank_you', label: 'Thank you note', type: 'textarea', group: 'texts' },
  { key: 'privacy_notice', label: 'Privacy notice', type: 'textarea', group: 'texts' },
];

const GROUPS: Array<{ key: Field['group']; title: string; blurb: string }> = [
  { key: 'window', title: 'Booking window', blurb: 'Controls whether customers can book right now.' },
  { key: 'limits', title: 'Limits', blurb: 'Packet caps per item and per booking.' },
  { key: 'verification', title: 'Customer verification', blurb: 'One-time code before online payment.' },
  { key: 'texts', title: 'Texts shown to customers', blurb: 'English values; Hindi and Gujarati are entered per item and setting later.' },
];

const KNOWN_KEYS = new Set(FIELDS.map((f) => f.key));

type Item = {
  id: number | null;
  nameEn: string;
  nameHi: string;
  nameGu: string;
  packSize: string;
  pricePaise: number;
  weightKg: number;
  active: boolean;
  sortOrder: number;
};

type StaffRow = { id: number; email: string; name: string; role: string; active: boolean };

const EMPTY_ITEM: Item = {
  id: null, nameEn: '', nameHi: '', nameGu: '', packSize: '',
  pricePaise: 0, weightKg: 1, active: true, sortOrder: 0,
};

const EMPTY_STAFF = { email: '', name: '', role: 'COUNTER', active: true };

const DELETE_ALL_PHRASE = 'DELETE ALL BOOKINGS';

// The API stores instants with an IST offset; datetime-local wants a naive value.
function toInput(v: string): string {
  const m = (v || '').match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})/);
  return m ? m[1] : v || '';
}
function fromInput(v: string): string {
  if (!v) return '';
  if (/[zZ]|[+-]\d{2}:\d{2}$/.test(v)) return v;
  return v.length === 16 ? `${v}:00+05:30` : `${v}+05:30`;
}

export default function AdminSettingsPage() {
  const me = useMe();
  const { toast, show } = useToast();
  const [error, setError] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});
  const [loaded, setLoaded] = useState<Record<string, string>>({});
  const [extraKeys, setExtraKeys] = useState<string[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [draft, setDraft] = useState<Item>(EMPTY_ITEM);
  const [staff, setStaff] = useState<StaffRow[]>([]);
  const [staffDraft, setStaffDraft] = useState({ ...EMPTY_STAFF });
  const [deleteConfirm, setDeleteConfirm] = useState('');
  const [deleting, setDeleting] = useState(false);

  function flash(msg: string) {
    setError('');
    show(msg);
  }

  async function loadAll() {
    try {
      const s = await api<Record<string, string>>('/api/admin/settings?lang=en');
      const display: Record<string, string> = {};
      Object.keys(s).forEach((k) => {
        display[k] = s[k] ?? '';
      });
      FIELDS.forEach((f) => {
        if (display[f.key] == null) display[f.key] = '';
        if (f.type === 'datetime') display[f.key] = toInput(display[f.key]);
      });
      setValues(display);
      setLoaded(display);
      setExtraKeys(Object.keys(s).filter((k) => !KNOWN_KEYS.has(k)).sort());
      setItems(await api<Item[]>('/api/admin/items'));
      setStaff(await api<StaffRow[]>('/api/admin/staff'));
    } catch (e) {
      setError((e as Error).message);
      show((e as Error).message, 'err');
    }
  }

  const allowed = canConfigure(me?.role);
  useEffect(() => {
    if (allowed) loadAll();
  }, [allowed]);

  async function saveSettings() {
    const rows: { key: string; language: string; value: string }[] = [];
    for (const k of Object.keys(values)) {
      if (values[k] === loaded[k]) continue;
      const field = FIELDS.find((f) => f.key === k);
      rows.push({ key: k, language: 'en', value: field?.type === 'datetime' ? fromInput(values[k]) : values[k] });
    }
    if (rows.length === 0) {
      flash('Nothing changed.');
      return;
    }
    try {
      await api('/api/admin/settings', { method: 'POST', body: JSON.stringify(rows) });
      flash(`Saved ${rows.length} setting${rows.length > 1 ? 's' : ''}.`);
      await loadAll();
    } catch (e) {
      setError((e as Error).message);
      show((e as Error).message, 'err');
    }
  }

  async function saveItem(it: Item) {
    try {
      await api('/api/admin/items', {
        method: 'POST',
        body: JSON.stringify({ ...it, weightKg: it.weightKg }),
      });
      flash(`Saved ${it.nameEn || 'item'}.`);
      setItems(await api<Item[]>('/api/admin/items'));
    } catch (e) {
      setError((e as Error).message);
      show((e as Error).message, 'err');
    }
  }

  async function saveStaff(row: { id?: number; email: string; name: string; role: string; active: boolean }) {
    try {
      await api('/api/admin/staff', { method: 'POST', body: JSON.stringify(row) });
      flash(`Saved ${row.email}.`);
      setStaff(await api<StaffRow[]>('/api/admin/staff'));
      setStaffDraft({ ...EMPTY_STAFF });
    } catch (e) {
      setError((e as Error).message);
      show((e as Error).message, 'err');
    }
  }

  async function deleteAllBookings() {
    if (!window.confirm('Permanently delete ALL bookings, orders, payments, OTP codes and cash handovers? This cannot be undone.')) return;
    setDeleting(true);
    try {
      const res = await api<{ deleted: Record<string, number> }>('/api/admin/bookings/delete-all', {
        method: 'POST',
        body: JSON.stringify({ confirm: deleteConfirm }),
      });
      setDeleteConfirm('');
      flash(`Deleted ${res.deleted?.bookings ?? 0} bookings and ${res.deleted?.orders ?? 0} orders. Next booking will be JB-0001.`);
    } catch (e) {
      setError((e as Error).message);
      show((e as Error).message, 'err');
    } finally {
      setDeleting(false);
    }
  }

  function patchItem(id: number | null, patch: Partial<Item>) {
    setItems((list) => list.map((it) => (it.id === id ? { ...it, ...patch } : it)));
  }

  const dirty = Object.keys(values).some((k) => values[k] !== loaded[k]);
  const bookingsOpen = (loaded.booking_enabled || 'true').toLowerCase() === 'true';

  if (me === undefined) return <main className="container muted">Loading…</main>;
  if (!me) return <SignInRequired />;
  if (!allowed) {
    return (
      <AdminShell active="settings" title="Settings" me={me}>
        <div className="card error" role="alert">
          <h2>Super Admin only</h2>
          <p>Settings can only be viewed and changed by a Super Admin. Ask a Super Admin if something needs to change.</p>
        </div>
      </AdminShell>
    );
  }

  return (
    <AdminShell active="settings" title="Settings" me={me}>
      {error && (
        <div className="card error" role="alert">
          {error}
        </div>
      )}

      {GROUPS.map((g) => (
        <section className="card" key={g.key}>
          <h2>{g.title}</h2>
          <p className="muted">{g.blurb}</p>
          <div className="form">
              {FIELDS.filter((f) => f.group === g.key).map((f) => (
            <label key={f.key}>
              <span>{f.label}</span>
              {f.type === 'bool' ? (
                <input
                  type="checkbox"
                  checked={(values[f.key] || '').toLowerCase() === 'true'}
                  onChange={(e) => setValues((v) => ({ ...v, [f.key]: String(e.target.checked) }))}
                />
              ) : f.type === 'textarea' ? (
                <textarea
                  rows={4}
                  value={values[f.key] || ''}
                  onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                />
              ) : f.type === 'select' ? (
                <select
                  value={values[f.key] || ''}
                  onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                >
                  {(f.options || []).map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                </select>
              ) : (
                <input
                  type={f.type === 'datetime' ? 'datetime-local' : f.type === 'number' ? 'number' : 'text'}
                  value={values[f.key] || ''}
                  onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                />
              )}
              {f.hint && <em className="muted">{f.hint}</em>}
            </label>
          ))}
          </div>
        </section>
      ))}

      {extraKeys.length > 0 && (
        <section className="card">
          <h2>Other settings</h2>
          <div className="form">
            {extraKeys.map((k) => (
              <label key={k}>
                <span>{k}</span>
                <input value={values[k] || ''} onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.value }))} />
              </label>
            ))}
          </div>
        </section>
      )}

      <div className="save-bar no-print">
        <span className="muted">{dirty ? 'You have unsaved changes.' : 'All settings saved.'}</span>
        <button type="button" className="btn gold" onClick={saveSettings} disabled={!dirty}>
          Save settings
        </button>
      </div>

      <section className="card">
        <h2>Item catalogue</h2>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name (en)</th>
                <th>Name (hi)</th>
                <th>Name (gu)</th>
                <th>Pack</th>
                <th>Price (₹)</th>
                <th>Weight (kg)</th>
                <th>Order</th>
                <th>Active</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {items.map((it) => (
                <tr key={String(it.id)}>
                  <td>
                    <input value={it.nameEn} onChange={(e) => patchItem(it.id, { nameEn: e.target.value })} />
                  </td>
                  <td>
                    <input value={it.nameHi} onChange={(e) => patchItem(it.id, { nameHi: e.target.value })} />
                  </td>
                  <td>
                    <input value={it.nameGu} onChange={(e) => patchItem(it.id, { nameGu: e.target.value })} />
                  </td>
                  <td>
                    <input value={it.packSize} onChange={(e) => patchItem(it.id, { packSize: e.target.value })} />
                  </td>
                  <td>
                    <input
                      type="number"
                      step="0.01"
                      value={it.pricePaise / 100}
                      onChange={(e) =>
                        patchItem(it.id, { pricePaise: Math.round((Number(e.target.value) || 0) * 100) })
                      }
                    />
                  </td>
                  <td>
                    <input
                      type="number"
                      step="0.001"
                      value={it.weightKg}
                      onChange={(e) => patchItem(it.id, { weightKg: Number(e.target.value) || 0 })}
                    />
                  </td>
                  <td>
                    <input
                      type="number"
                      value={it.sortOrder}
                      onChange={(e) => patchItem(it.id, { sortOrder: Number(e.target.value) || 0 })}
                    />
                  </td>
                  <td>
                    <input
                      type="checkbox"
                      checked={it.active}
                      onChange={(e) => patchItem(it.id, { active: e.target.checked })}
                    />
                  </td>
                  <td>
                    <button className="btn secondary" onClick={() => saveItem(it)}>
                      Save
                    </button>
                  </td>
                </tr>
              ))}
              <tr>
                <td>
                  <input
                    placeholder="New item"
                    value={draft.nameEn}
                    onChange={(e) => setDraft({ ...draft, nameEn: e.target.value })}
                  />
                </td>
                <td>
                  <input value={draft.nameHi} onChange={(e) => setDraft({ ...draft, nameHi: e.target.value })} />
                </td>
                <td>
                  <input value={draft.nameGu} onChange={(e) => setDraft({ ...draft, nameGu: e.target.value })} />
                </td>
                <td>
                  <input
                    placeholder="500 g box"
                    value={draft.packSize}
                    onChange={(e) => setDraft({ ...draft, packSize: e.target.value })}
                  />
                </td>
                <td>
                  <input
                    type="number"
                    step="0.01"
                    value={draft.pricePaise / 100}
                    onChange={(e) =>
                      setDraft({ ...draft, pricePaise: Math.round((Number(e.target.value) || 0) * 100) })
                    }
                  />
                </td>
                <td>
                  <input
                    type="number"
                    step="0.001"
                    value={draft.weightKg}
                    onChange={(e) => setDraft({ ...draft, weightKg: Number(e.target.value) || 0 })}
                  />
                </td>
                <td>
                  <input
                    type="number"
                    value={draft.sortOrder}
                    onChange={(e) => setDraft({ ...draft, sortOrder: Number(e.target.value) || 0 })}
                  />
                </td>
                <td>
                  <input
                    type="checkbox"
                    checked={draft.active}
                    onChange={(e) => setDraft({ ...draft, active: e.target.checked })}
                  />
                </td>
                <td>
                  <button
                    className="btn"
                    disabled={!draft.nameEn.trim() || !draft.packSize.trim()}
                    onClick={() => saveItem(draft).then(() => setDraft(EMPTY_ITEM))}
                  >
                    Add
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p className="muted">
          Prices are stored as paise on the API and shown here in rupees. Unchecking Active hides the item
          from booking screens without deleting its history.
        </p>
      </section>

      <section className="card">
        <h2>Staff</h2>
        <p className="muted">
          Only active staff with an entry here can sign in with Google. Roles: SUPER_ADMIN sees everything,
          including this page. ADMIN sees the dashboard and bookings (export, void, counter). TREASURER has
          the same access plus marking payments reconciled. COUNTER can issue counter bookings.
        </p>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Email</th>
                <th>Name</th>
                <th>Role</th>
                <th>Active</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {staff.map((s) => (
                <tr key={s.id}>
                  <td>{s.email}</td>
                  <td>
                    <input
                      value={s.name}
                      onChange={(e) =>
                        setStaff((list) =>
                          list.map((r) => (r.id === s.id ? { ...r, name: e.target.value } : r)),
                        )
                      }
                    />
                  </td>
                  <td>
                    <select
                      value={s.role}
                      onChange={(e) =>
                        setStaff((list) =>
                          list.map((r) => (r.id === s.id ? { ...r, role: e.target.value } : r)),
                        )
                      }
                    >
                      {ROLES.map((r) => (
                        <option key={r} value={r}>
                          {r}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <input
                      type="checkbox"
                      checked={s.active}
                      onChange={(e) =>
                        setStaff((list) =>
                          list.map((r) => (r.id === s.id ? { ...r, active: e.target.checked } : r)),
                        )
                      }
                    />
                  </td>
                  <td>
                    <button className="btn secondary" onClick={() => saveStaff(s)}>
                      Save
                    </button>
                  </td>
                </tr>
              ))}
              <tr>
                <td>
                  <input
                    placeholder="name@example.com"
                    value={staffDraft.email}
                    onChange={(e) => setStaffDraft({ ...staffDraft, email: e.target.value })}
                  />
                </td>
                <td>
                  <input
                    placeholder="Full name"
                    value={staffDraft.name}
                    onChange={(e) => setStaffDraft({ ...staffDraft, name: e.target.value })}
                  />
                </td>
                <td>
                  <select
                    value={staffDraft.role}
                    onChange={(e) => setStaffDraft({ ...staffDraft, role: e.target.value })}
                  >
                    {ROLES.map((r) => (
                      <option key={r} value={r}>
                        {r}
                      </option>
                    ))}
                  </select>
                </td>
                <td>
                  <input
                    type="checkbox"
                    checked={staffDraft.active}
                    onChange={(e) => setStaffDraft({ ...staffDraft, active: e.target.checked })}
                  />
                </td>
                <td>
                  <button
                    className="btn"
                    disabled={!staffDraft.email.includes('@')}
                    onClick={() => saveStaff(staffDraft)}
                  >
                    Add
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section className="card" style={{ borderColor: 'var(--err)' }}>
        <h2 style={{ color: 'var(--err)' }}>Danger zone</h2>
        <p className="muted small">
          Delete all bookings to clear test data before going live. This permanently removes every booking, order,
          payment, notification, OTP code and cash handover, and resets booking IDs so the next one is JB-0001. The
          audit log is kept and records who did this.
        </p>
        {bookingsOpen && (
          <p className="error">Turn &quot;Bookings open&quot; off and save settings first.</p>
        )}
        <div className="form">
          <label>
            <span>
              Type <strong>{DELETE_ALL_PHRASE}</strong> to confirm
            </span>
            <input
              value={deleteConfirm}
              onChange={(e) => setDeleteConfirm(e.target.value)}
              placeholder={DELETE_ALL_PHRASE}
              autoComplete="off"
              aria-label="Delete all bookings confirmation"
            />
          </label>
        </div>
        <button
          type="button"
          className="btn"
          style={{ background: 'var(--err)', marginTop: 8 }}
          disabled={deleting || deleteConfirm.trim() !== DELETE_ALL_PHRASE || bookingsOpen}
          onClick={deleteAllBookings}
        >
          {deleting ? 'Deleting…' : 'Delete all bookings'}
        </button>
      </section>
      <Toast toast={toast} />
    </AdminShell>
  );
}
