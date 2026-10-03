'use client';

import { useEffect, useState } from 'react';
import { api } from '../../../lib/format';

type Field = {
  key: string;
  label: string;
  type: 'bool' | 'number' | 'text' | 'textarea' | 'datetime' | 'select';
  hint?: string;
  options?: string[];
};

const FIELDS: Field[] = [
  { key: 'booking_enabled', label: 'Bookings open', type: 'bool', hint: 'Turn off to stop new bookings without deploying.' },
  { key: 'booking_window_open', label: 'Window opens', type: 'datetime', hint: 'IST. Leave blank to open immediately.' },
  { key: 'booking_window_close', label: 'Window closes', type: 'datetime', hint: 'IST. Leave blank to stay open.' },
  { key: 'max_packets_per_item', label: 'Max packets per item', type: 'number' },
  { key: 'max_packets_total', label: 'Max packets per booking', type: 'number' },
  { key: 'allowed_pins', label: 'Allowed pincodes', type: 'text', hint: 'Comma separated: 411001,411005 or a range 411001-411062.' },
  { key: 'otp_provider', label: 'OTP provider', type: 'select', options: ['email', 'sms'], hint: 'sms stays disabled until an SMS gateway is wired up.' },
  { key: 'title', label: 'Page title', type: 'text' },
  { key: 'subtitle', label: 'Subtitle', type: 'text' },
  { key: 'notice', label: 'Notice on booking page', type: 'textarea' },
  { key: 'terms', label: 'Terms', type: 'textarea' },
  { key: 'thank_you', label: 'Thank you note', type: 'textarea' },
  { key: 'privacy_notice', label: 'Privacy notice', type: 'textarea' },
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
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [values, setValues] = useState<Record<string, string>>({});
  const [loaded, setLoaded] = useState<Record<string, string>>({});
  const [extraKeys, setExtraKeys] = useState<string[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [draft, setDraft] = useState<Item>(EMPTY_ITEM);
  const [staff, setStaff] = useState<StaffRow[]>([]);
  const [staffDraft, setStaffDraft] = useState({ ...EMPTY_STAFF });

  function flash(msg: string) {
    setError('');
    setNotice(msg);
    window.setTimeout(() => setNotice(''), 4000);
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
    }
  }

  useEffect(() => {
    loadAll();
  }, []);

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
    }
  }

  function patchItem(id: number | null, patch: Partial<Item>) {
    setItems((list) => list.map((it) => (it.id === id ? { ...it, ...patch } : it)));
  }

  const dirty = Object.keys(values).some((k) => values[k] !== loaded[k]);

  return (
    <main className="container" style={{ maxWidth: 960 }}>
      <h1>Settings</h1>
      <p>
        <a href="/admin">← Admin</a>
      </p>
      {error && <div className="error">{error}</div>}
      {notice && <div className="card" style={{ borderColor: 'var(--ok)' }}>{notice}</div>}

      <div className="card">
        <h2>Booking</h2>
        <div className="form">
          {FIELDS.map((f) => (
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
          {extraKeys.map((k) => (
            <label key={k}>
              <span>{k} (extra)</span>
              <input
                value={values[k] || ''}
                onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.value }))}
              />
            </label>
          ))}
        </div>
        <p className="muted">Saved values apply to the English site only.</p>
        <button className="btn" onClick={saveSettings} disabled={!dirty}>
          Save settings
        </button>
      </div>

      <div className="card">
        <h2>Item catalogue</h2>
        <div style={{ overflowX: 'auto' }}>
          <table className="admin-table">
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
      </div>

      <div className="card">
        <h2>Staff</h2>
        <p className="muted">
          Only active staff with an entry here can sign in with Google. Roles: ADMIN sees everything,
          COUNTER can issue counter bookings.
        </p>
        <div style={{ overflowX: 'auto' }}>
          <table className="admin-table">
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
                      <option value="ADMIN">ADMIN</option>
                      <option value="COUNTER">COUNTER</option>
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
                    <option value="ADMIN">ADMIN</option>
                    <option value="COUNTER">COUNTER</option>
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
      </div>
    </main>
  );
}
