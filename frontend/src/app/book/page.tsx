'use client';

import { useEffect, useMemo, useState } from 'react';
import { t, type Lang } from '../../i18n/dicts';
import { api, ApiError, formatINR } from '../../lib/format';
import {
  CUSTOMER_FIELDS,
  validateCustomer,
  validateField,
  validateQuantities,
  type CustomerForm,
  type FieldName,
} from '../../lib/validators';
import { AppHeader } from '../../components/AppHeader';
import { Stepper } from '../../components/Stepper';
import { QtyStepper } from '../../components/QtyStepper';
import { Field } from '../../components/Field';

type Item = { id: number; name: string; packSize: string; pricePaise: number; weightKg: number };

type Config = {
  title: string;
  subtitle: string;
  notice: string;
  terms: string;
  thankYou: string;
  privacyNotice: string;
  bookingEnabled: boolean;
  maxPerItem: number;
  maxTotal: number;
  otpRequired: boolean;
  otpChannel: 'sms' | 'email';
  items: Item[];
};

type Errors = Partial<Record<FieldName, string>>;
type OtpState = { code: string; sent: boolean; destination: string; devCode: string };

const EMPTY_FORM: CustomerForm = { name: '', mobile: '', email: '', address: '', pinCode: '' };
const NO_OTP: OtpState = { code: '', sent: false, destination: '', devCode: '' };
const DEFAULT_MAX_PER_ITEM = 20;
const DEFAULT_MAX_TOTAL = 50;
const STEPS = 3;

export default function BookPage() {
  const [lang, setLang] = useState<Lang>('en');
  const [cfg, setCfg] = useState<Config | null>(null);
  const [step, setStep] = useState(1);
  const [qty, setQty] = useState<Record<number, number>>({});
  const [form, setForm] = useState<CustomerForm>(EMPTY_FORM);
  const [errors, setErrors] = useState<Errors>({});
  const [otp, setOtp] = useState<OtpState>(NO_OTP);
  const [accepted, setAccepted] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');

  useEffect(() => {
    setLang((localStorage.getItem('jb_lang') as Lang) || 'en');
  }, []);

  useEffect(() => {
    api<Config>(`/api/public/config?lang=${lang}`).then(setCfg).catch((e) => setError((e as Error).message));
  }, [lang]);

  useEffect(() => {
    if (cfg) document.title = cfg.title;
  }, [cfg]);

  const otpByEmail = !!cfg?.otpRequired && cfg.otpChannel === 'email';
  const otpField: keyof CustomerForm = otpByEmail ? 'email' : 'mobile';
  const fieldOpts = useMemo(() => ({ emailRequired: otpByEmail }), [otpByEmail]);
  const limits = {
    maxPerItem: cfg && cfg.maxPerItem > 0 ? cfg.maxPerItem : DEFAULT_MAX_PER_ITEM,
    maxTotal: cfg && cfg.maxTotal > 0 ? cfg.maxTotal : DEFAULT_MAX_TOTAL,
  };

  const items = cfg?.items ?? [];
  const packets = items.reduce((sum, it) => sum + (qty[it.id] || 0), 0);
  const amount = items.reduce((sum, it) => sum + it.pricePaise * (qty[it.id] || 0), 0);
  const weightKg = items.reduce((sum, it) => sum + (it.weightKg || 0) * (qty[it.id] || 0), 0);
  const qtyError = validateQuantities(qty, limits);
  const customerValid = Object.keys(validateCustomer(form, fieldOpts)).length === 0;
  const otpValid = !cfg?.otpRequired || (otp.sent && validateField('otp', otp.code) === null);
  const lines = Object.entries(qty)
    .filter(([, q]) => q > 0)
    .map(([itemId, quantity]) => ({ itemId: Number(itemId), quantity }));

  function setLangAndSave(l: Lang) {
    setLang(l);
    localStorage.setItem('jb_lang', l);
  }

  /** Re-check a field only once it has shown an error, so messages clear as the user types. */
  function setField(field: keyof CustomerForm, value: string) {
    setForm((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) {
      setErrors((prev) => ({ ...prev, [field]: validateField(field, value, fieldOpts) ?? undefined }));
    }
    if (field === otpField && otp.sent) setOtp(NO_OTP);
  }

  function blurField(field: FieldName, value: string) {
    setErrors((prev) => ({ ...prev, [field]: validateField(field, value, fieldOpts) ?? undefined }));
  }

  function showApiError(e: unknown) {
    const err = e as ApiError;
    const field = err instanceof ApiError ? err.field : undefined;
    if (field && (CUSTOMER_FIELDS as readonly string[]).includes(field)) {
      setErrors((prev) => ({ ...prev, [field]: err.message }));
      setStep(2);
      return;
    }
    if (field === 'otp') {
      setErrors((prev) => ({ ...prev, otp: err.message }));
      setStep(2);
      return;
    }
    if (field === 'items') setStep(1);
    setError(err.message || String(e));
  }

  async function sendOtp() {
    if (!cfg) return;
    const destination = form[otpField];
    const key = validateField(otpField, destination, fieldOpts);
    if (key) {
      setErrors((prev) => ({ ...prev, [otpField]: key }));
      return;
    }
    setBusy(true);
    setError('');
    try {
      const res = await api<{ devCode?: string; destination: string }>('/api/public/otp/request', {
        method: 'POST',
        body: JSON.stringify({ mobile: form.mobile, email: form.email }),
      });
      setOtp({ code: '', sent: true, destination: res.destination, devCode: res.devCode || '' });
      setErrors((prev) => ({ ...prev, otp: undefined }));
    } catch (e) {
      showApiError(e);
    } finally {
      setBusy(false);
    }
  }

  async function pay() {
    setBusy(true);
    setError('');
    setInfo('');
    try {
      const order = await api<{ orderId: string; amountPaise: number; gateway?: { keyId?: string } }>(
        '/api/public/orders',
        {
          method: 'POST',
          body: JSON.stringify({ items: lines, ...form, acceptedTerms: accepted, otpCode: otp.code }),
        },
      );
      // Scaffold: with Razorpay keys the hosted checkout opens here using order.gateway.keyId.
      setInfo(
        `${t(lang, 'orderCreated')}: ${order.orderId} · ${formatINR(order.amountPaise)}. ` +
          (order.gateway ? 'Razorpay checkout would open here.' : t(lang, 'paymentNotConfigured')),
      );
    } catch (e) {
      showApiError(e);
    } finally {
      setBusy(false);
    }
  }

  function goTo(next: number) {
    if (next < 1 || next > STEPS) return;
    setError('');
    setStep(next);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  if (!cfg) {
    return (
      <>
        <AppHeader title={t(lang, 'title')} backHref="/" />
        <main className="container">
          {error ? <div className="card error">{error}</div> : <p className="muted">{t(lang, 'loading')}</p>}
        </main>
      </>
    );
  }

  const stepLabels = [t(lang, 'step1'), t(lang, 'step2'), t(lang, 'step3')];
  const err = (f: FieldName) => (errors[f] ? t(lang, errors[f] as string) : null);

  return (
    <>
      <AppHeader title={cfg.title} subtitle={cfg.subtitle} lang={lang} onLang={setLangAndSave} backHref="/" />
      <main className="container">
        {cfg.notice && <div className="card info">{cfg.notice}</div>}
        {!cfg.bookingEnabled && <div className="card error">{t(lang, 'bookingClosed')}</div>}

        <Stepper step={step} labels={stepLabels} onGo={goTo} />

        {error && (
          <div className="card error" role="alert">
            {error}
          </div>
        )}
        {info && <div className="card info">{info}</div>}

        {step === 1 && (
          <section className="card">
            <h2>{t(lang, 'chooseSweets')}</h2>
            <p className="muted">{t(lang, 'chooseSweetsHelp')}</p>
            <div className="item-list">
              {items.map((item) => {
                const q = qty[item.id] || 0;
                return (
                  <div className={`item-card${q > 0 ? ' selected' : ''}`} key={item.id}>
                    <div>
                      <div className="name">{item.name}</div>
                      <div className="meta">
                        {item.packSize} · <span className="price">{formatINR(item.pricePaise)}</span> {t(lang, 'each')}
                      </div>
                    </div>
                    <QtyStepper
                      value={q}
                      max={limits.maxPerItem}
                      label={item.name}
                      onChange={(n) => setQty((prev) => ({ ...prev, [item.id]: n }))}
                    />
                    {q > 0 && (
                      <div className="line-total">
                        {t(lang, 'lineTotal')}
                        <strong>{formatINR(item.pricePaise * q)}</strong>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            {qtyError && packets > 0 && (
              <p className="field-error" role="alert">
                {t(lang, qtyError)}
              </p>
            )}
          </section>
        )}

        {step === 2 && (
          <section className="card">
            <h2>{t(lang, 'step2')}</h2>
            <p className="muted">{t(lang, 'detailsHelp')}</p>

            <Field id="name" label={t(lang, 'name')} error={err('name')}>
              <input
                id="name"
                autoComplete="name"
                value={form.name}
                onChange={(e) => setField('name', e.target.value)}
                onBlur={(e) => blurField('name', e.target.value)}
                aria-invalid={!!errors.name}
              />
            </Field>

            <Field id="mobile" label={t(lang, 'mobile')} error={err('mobile')}>
              <input
                id="mobile"
                inputMode="numeric"
                autoComplete="tel-national"
                maxLength={10}
                value={form.mobile}
                onChange={(e) => setField('mobile', e.target.value.replace(/\D/g, ''))}
                onBlur={(e) => blurField('mobile', e.target.value)}
                aria-invalid={!!errors.mobile}
              />
            </Field>

            <Field
              id="email"
              label={otpByEmail ? t(lang, 'emailForOtp') : t(lang, 'email')}
              error={err('email')}
            >
              <input
                id="email"
                type="email"
                inputMode="email"
                autoComplete="email"
                value={form.email}
                onChange={(e) => setField('email', e.target.value)}
                onBlur={(e) => blurField('email', e.target.value)}
                aria-invalid={!!errors.email}
              />
            </Field>

            {cfg.otpRequired && (
              <div className="otp-box">
                {!otp.sent ? (
                  <>
                    <p className="muted">{t(lang, otpByEmail ? 'otpHelpEmail' : 'otpHelpSms')}</p>
                    <button
                      type="button"
                      className="btn secondary block"
                      disabled={busy || !!validateField(otpField, form[otpField], fieldOpts)}
                      onClick={sendOtp}
                    >
                      {t(lang, 'sendOtp')}
                    </button>
                  </>
                ) : (
                  <>
                    <p className="muted">
                      {t(lang, 'otpSentTo')} <strong>{otp.destination}</strong>
                    </p>
                    <Field id="otp" label={t(lang, 'otp')} error={err('otp')}>
                      <input
                        id="otp"
                        inputMode="numeric"
                        autoComplete="one-time-code"
                        maxLength={6}
                        value={otp.code}
                        onChange={(e) => {
                          const code = e.target.value.replace(/\D/g, '');
                          setOtp((prev) => ({ ...prev, code }));
                          if (errors.otp) setErrors((prev) => ({ ...prev, otp: validateField('otp', code) ?? undefined }));
                        }}
                        onBlur={(e) => blurField('otp', e.target.value)}
                        aria-invalid={!!errors.otp}
                      />
                    </Field>
                    {otp.devCode && <p className="muted small">Dev OTP: {otp.devCode}</p>}
                    <button type="button" className="btn ghost sm" disabled={busy} onClick={sendOtp}>
                      {t(lang, 'resendOtp')}
                    </button>
                  </>
                )}
              </div>
            )}

            <Field id="address" label={t(lang, 'address')} error={err('address')}>
              <textarea
                id="address"
                rows={3}
                autoComplete="street-address"
                value={form.address}
                onChange={(e) => setField('address', e.target.value)}
                onBlur={(e) => blurField('address', e.target.value)}
                aria-invalid={!!errors.address}
              />
            </Field>

            <Field id="pinCode" label={t(lang, 'pin')} error={err('pinCode')}>
              <input
                id="pinCode"
                inputMode="numeric"
                autoComplete="postal-code"
                maxLength={6}
                value={form.pinCode}
                onChange={(e) => setField('pinCode', e.target.value.replace(/\D/g, ''))}
                onBlur={(e) => blurField('pinCode', e.target.value)}
                aria-invalid={!!errors.pinCode}
              />
            </Field>
          </section>
        )}

        {step === 3 && (
          <>
            <section className="card">
              <div className="spread">
                <h2>{t(lang, 'yourOrder')}</h2>
                <button type="button" className="btn ghost sm" onClick={() => goTo(1)}>
                  {t(lang, 'edit')}
                </button>
              </div>
              <ul className="summary-list">
                {items
                  .filter((it) => (qty[it.id] || 0) > 0)
                  .map((it) => (
                    <li key={it.id}>
                      <span>
                        {it.name}
                        <div className="qty">
                          {qty[it.id]} × {it.packSize} · {formatINR(it.pricePaise)}
                        </div>
                      </span>
                      <strong>{formatINR(it.pricePaise * qty[it.id])}</strong>
                    </li>
                  ))}
              </ul>
              <div className="summary-total">
                <span>
                  {t(lang, 'total')} · {packets} {t(lang, 'packets')} · {weightKg.toFixed(2)} {t(lang, 'kg')}
                </span>
                <span className="amt">{formatINR(amount)}</span>
              </div>
            </section>

            <section className="card">
              <div className="spread">
                <h2>{t(lang, 'step2')}</h2>
                <button type="button" className="btn ghost sm" onClick={() => goTo(2)}>
                  {t(lang, 'edit')}
                </button>
              </div>
              <p>
                <strong>{form.name}</strong> · {form.mobile}
                {form.email && (
                  <>
                    <br />
                    {form.email}
                  </>
                )}
                <br />
                <span className="muted">
                  {form.address} — {form.pinCode}
                </span>
              </p>
            </section>

            <section className="card">
              <h2>{t(lang, 'beforeYouPay')}</h2>
              <p className="muted">{t(lang, 'reviewHelp')}</p>
              <div className="terms-box">{cfg.terms}</div>
              <hr className="festive-rule" />
              <p className="muted small">{cfg.privacyNotice}</p>
              <label className="check">
                <input type="checkbox" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} />
                <span>{t(lang, 'acceptTerms')}</span>
              </label>
            </section>
          </>
        )}
      </main>

      <div className="bottom-bar no-print">
        <div className="inner">
          <div className="totals">
            <div className="amt">{formatINR(amount)}</div>
            <div className="sub">
              {packets} {t(lang, 'packets')} · {weightKg.toFixed(2)} {t(lang, 'kg')}
            </div>
          </div>
          <div className="actions">
            {step > 1 && (
              <button type="button" className="btn secondary" onClick={() => goTo(step - 1)}>
                {t(lang, 'back')}
              </button>
            )}
            {step === 1 && (
              <button type="button" className="btn" disabled={!!qtyError || !cfg.bookingEnabled} onClick={() => goTo(2)}>
                {t(lang, 'next')}
              </button>
            )}
            {step === 2 && (
              <button type="button" className="btn" disabled={!customerValid || !otpValid} onClick={() => goTo(3)}>
                {t(lang, 'next')}
              </button>
            )}
            {step === 3 && (
              <button type="button" className="btn gold" disabled={!accepted || busy || !cfg.bookingEnabled} onClick={pay}>
                {t(lang, 'pay')} · {formatINR(amount)}
              </button>
            )}
          </div>
        </div>
      </div>
    </>
  );
}
