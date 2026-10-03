'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, ApiError, formatINR } from '../../../lib/format';
import { validateCustomer, validateField, validateQuantities, type CustomerForm, type FieldName } from '../../../lib/validators';
import { AdminShell } from '../../../components/AdminShell';
import { useMe } from '../../../components/useMe';
import { SignInRequired } from '../../../components/SignInRequired';
import { Field } from '../../../components/Field';
import { QtyStepper } from '../../../components/QtyStepper';
import { t } from '../../../i18n/dicts';

type Item = { id: number; nameEn: string; packSize: string; pricePaise: number; active: boolean };
type Limits = { maxPerItem: number; maxTotal: number };
type Method = 'cash' | 'upi';
type Errors = Partial<Record<FieldName, string>>;

const EMPTY_FORM: CustomerForm = { name: '', mobile: '', email: '', address: '', pinCode: '' };
const DEFAULT_LIMITS: Limits = { maxPerItem: 20, maxTotal: 50 };
const COUNTER_FIELDS: Array<keyof CustomerForm> = ['name', 'mobile', 'address', 'pinCode'];

export default function CounterPage() {
  const router = useRouter();
  const me = useMe();
  const [items, setItems] = useState<Item[]>([]);
  const [limits, setLimits] = useState<Limits>(DEFAULT_LIMITS);
  const [qty, setQty] = useState<Record<number, number>>({});
  const [form, setForm] = useState<CustomerForm>(EMPTY_FORM);
  const [errors, setErrors] = useState<Errors>({});
  const [method, setMethod] = useState<Method>('cash');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api<Item[]>('/api/admin/items').then((list) => setItems(list.filter((i) => i.active !== false))).catch(() => {});
    api<Limits>('/api/public/config')
      .then((c) =>
        setLimits({
          maxPerItem: c.maxPerItem > 0 ? c.maxPerItem : DEFAULT_LIMITS.maxPerItem,
          maxTotal: c.maxTotal > 0 ? c.maxTotal : DEFAULT_LIMITS.maxTotal,
        }),
      )
      .catch(() => {});
  }, []);

  const fieldOpts = {};
  const packets = items.reduce((sum, it) => sum + (qty[it.id] || 0), 0);
  const amount = items.reduce((sum, it) => sum + it.pricePaise * (qty[it.id] || 0), 0);
  const qtyError = validateQuantities(qty, limits);
  const customerErrors = validateCustomer(form, fieldOpts);
  const customerValid = COUNTER_FIELDS.every((f) => !customerErrors[f]);
  const canSubmit = customerValid && !qtyError && !busy;

  function setField(field: keyof CustomerForm, value: string) {
    setForm((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) setErrors((prev) => ({ ...prev, [field]: validateField(field, value, fieldOpts) ?? undefined }));
  }

  function blurField(field: FieldName, value: string) {
    setErrors((prev) => ({ ...prev, [field]: validateField(field, value, fieldOpts) ?? undefined }));
  }

  async function submit() {
    setError('');
    setBusy(true);
    try {
      const payload = {
        name: form.name,
        mobile: form.mobile,
        address: form.address,
        pinCode: form.pinCode,
        paymentMethod: method,
        items: Object.entries(qty)
          .filter(([, q]) => q > 0)
          .map(([itemId, quantity]) => ({ itemId: Number(itemId), quantity })),
        cashReceivedPaise: amount,
        upiReference: method === 'upi' ? 'UPI-STAFF-CONFIRMED' : undefined,
      };
      const data = await api<{ bookingId: string }>('/api/staff/counter/bookings', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      router.push(`/receipt/${data.bookingId}?print=1&from=counter`);
    } catch (e) {
      const err = e as ApiError;
      if (err instanceof ApiError && err.field && err.field !== 'items') {
        setErrors((prev) => ({ ...prev, [err.field as FieldName]: err.message }));
      } else {
        setError(err.message || String(e));
      }
      setBusy(false);
    }
  }

  if (me === undefined) return <main className="container muted">Loading…</main>;
  if (!me) return <SignInRequired />;

  const err = (f: FieldName) => (errors[f] ? t('en', errors[f] as string) : null);

  return (
    <AdminShell active="counter" title="Counter booking" me={me}>
      <p className="muted" style={{ marginTop: 4 }}>
        Taking booking as <strong>{me.name || me.email}</strong>
      </p>
      {error && (
        <div className="card error" role="alert">
          {error}
        </div>
      )}

      <section className="card">
        <h2>Customer</h2>
        <div className="form-grid two">
          <Field id="name" label="Customer name" error={err('name')}>
            <input id="name" value={form.name} onChange={(e) => setField('name', e.target.value)} onBlur={(e) => blurField('name', e.target.value)} aria-invalid={!!errors.name} />
          </Field>
          <Field id="mobile" label="Mobile" error={err('mobile')}>
            <input id="mobile" inputMode="numeric" maxLength={10} value={form.mobile} onChange={(e) => setField('mobile', e.target.value.replace(/\D/g, ''))} onBlur={(e) => blurField('mobile', e.target.value)} aria-invalid={!!errors.mobile} />
          </Field>
        </div>
        <Field id="address" label="Address" error={err('address')}>
          <textarea id="address" rows={2} value={form.address} onChange={(e) => setField('address', e.target.value)} onBlur={(e) => blurField('address', e.target.value)} aria-invalid={!!errors.address} />
        </Field>
        <Field id="pinCode" label="Pin code" error={err('pinCode')}>
          <input id="pinCode" inputMode="numeric" maxLength={6} value={form.pinCode} onChange={(e) => setField('pinCode', e.target.value.replace(/\D/g, ''))} onBlur={(e) => blurField('pinCode', e.target.value)} aria-invalid={!!errors.pinCode} />
        </Field>
      </section>

      <section className="card">
        <h2>Payment</h2>
        <div className="pay-toggle" role="radiogroup" aria-label="Payment method">
          <button type="button" role="radio" aria-checked={method === 'cash'} className={method === 'cash' ? 'active' : ''} onClick={() => setMethod('cash')}>
            Cash
            <small>exact amount received</small>
          </button>
          <button type="button" role="radio" aria-checked={method === 'upi'} className={method === 'upi' ? 'active' : ''} onClick={() => setMethod('upi')}>
            UPI
            <small>staff-confirmed</small>
          </button>
        </div>
      </section>

      <section className="card">
        <h2>Items</h2>
        <div className="item-list">
          {items.map((item) => {
            const q = qty[item.id] || 0;
            return (
              <div className={`item-card${q > 0 ? ' selected' : ''}`} key={item.id}>
                <div>
                  <div className="name">{item.nameEn}</div>
                  <div className="meta">
                    {item.packSize} · <span className="price">{formatINR(item.pricePaise)}</span>
                  </div>
                </div>
                <QtyStepper value={q} max={limits.maxPerItem} label={item.nameEn} onChange={(n) => setQty((prev) => ({ ...prev, [item.id]: n }))} />
                {q > 0 && (
                  <div className="line-total">
                    Line total<strong>{formatINR(item.pricePaise * q)}</strong>
                  </div>
                )}
              </div>
            );
          })}
        </div>
        {qtyError && packets > 0 && (
          <p className="field-error" role="alert">
            {t('en', qtyError)}
          </p>
        )}
      </section>

      <div className="bottom-bar staff no-print">
        <div className="inner" style={{ maxWidth: 1100 }}>
          <div className="totals">
            <div className="amt">{formatINR(amount)} due</div>
            <div className="sub">
              {packets} packets · {method === 'cash' ? 'Cash' : 'UPI'}
            </div>
          </div>
          <div className="actions">
            <button type="button" className="btn gold" disabled={!canSubmit} onClick={submit}>
              {busy ? 'Issuing…' : `Confirm ${method === 'cash' ? 'cash' : 'UPI'} & issue receipt`}
            </button>
          </div>
        </div>
      </div>
    </AdminShell>
  );
}
