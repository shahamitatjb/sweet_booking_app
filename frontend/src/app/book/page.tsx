'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { t, type Lang } from '../../i18n/dicts';
import { api, formatINR } from '../../lib/format';

type Item = {
  id: number;
  name: string;
  packSize: string;
  pricePaise: number;
  weightKg: number;
};

type Config = {
  title: string;
  subtitle: string;
  notice: string;
  terms: string;
  thankYou: string;
  privacyNotice: string;
  bookingEnabled: boolean;
  items: Item[];
};

export default function BookPage() {
  const router = useRouter();
  const [lang, setLang] = useState<Lang>('en');
  const [cfg, setCfg] = useState<Config | null>(null);
  const [step, setStep] = useState(1);
  const [qty, setQty] = useState<Record<number, number>>({});
  const [form, setForm] = useState({ name: '', mobile: '', email: '', address: '', pinCode: '', otp: '' });
  const [otpExpected, setOtpExpected] = useState('');
  const [otpSent, setOtpSent] = useState(false);
  const [accepted, setAccepted] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<{ packets: number; amountPaise: number; weightKg: number } | null>(null);

  useEffect(() => {
    const saved = (localStorage.getItem('jb_lang') as Lang) || 'en';
    setLang(saved);
  }, []);

  useEffect(() => {
    api<Config>(`/api/public/config?lang=${lang}`).then(setCfg).catch((e) => setError(e.message));
  }, [lang]);

  useEffect(() => {
    if (!cfg) return;
    api<{ packets: number; amountPaise: number; weightKg: number }>('/api/public/orders/preview', {
      method: 'POST',
      body: JSON.stringify({
        items: Object.entries(qty).map(([itemId, quantity]) => ({ itemId: Number(itemId), quantity })),
        ...form,
      }),
    }).then(setPreview).catch(() => setPreview(null));
  }, [qty, form, cfg]);

  function setLangAndSave(l: Lang) {
    setLang(l);
    localStorage.setItem('jb_lang', l);
  }

  async function sendOtp() {
    setError('');
    try {
      const res = await api<{ devCode?: string; channel: string }>('/api/public/otp/request', {
        method: 'POST',
        body: JSON.stringify({ destination: form.mobile, channel: 'email' }),
      });
      setOtpSent(true);
      if (res.devCode) setOtpExpected(res.devCode);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function pay() {
    setLoading(true);
    setError('');
    try {
      const order = await api<{ orderId: string; amountPaise: number; gateway?: { keyId?: string } }>(
        '/api/public/orders',
        {
          method: 'POST',
          body: JSON.stringify({
            items: Object.entries(qty).map(([itemId, quantity]) => ({ itemId: Number(itemId), quantity })),
            ...form,
            acceptedTerms: accepted,
            otpExpected,
          }),
        }
      );
      // Scaffold: without Razorpay keys, show booking id path is not paid yet.
      // With keys, open Razorpay checkout (script) using order.gateway.keyId + amount.
      alert(
        `Order created: ${order.orderId}\nAmount: ${formatINR(order.amountPaise)}\n` +
          (order.gateway
            ? 'Razorpay checkout would open here.'
            : 'Configure Razorpay keys to accept online payment. Counter bookings work without gateway.')
      );
      // Demo finalize helper when testing staging without live gateway:
      // POST /api/webhooks/razorpay/finalize-demo { orderId, amountPaise }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  if (!cfg) {
    return (
      <main className="container">
        <p className="muted">Loading…</p>
      </main>
    );
  }

  const packets = preview?.packets ?? 0;
  const amount = preview?.amountPaise ?? 0;

  return (
    <main className="container">
      <div className="lang-bar no-print">
        {(['en', 'hi', 'gu'] as Lang[]).map((l) => (
          <button key={l} className={lang === l ? 'active' : ''} onClick={() => setLangAndSave(l)}>
            {l.toUpperCase()}
          </button>
        ))}
      </div>

      <h1>{cfg.title}</h1>
      <p className="muted">{cfg.subtitle}</p>
      {cfg.notice && <div className="card">{cfg.notice}</div>}

      {!cfg.bookingEnabled && (
        <div className="card error">{t(lang, 'bookingClosed')}</div>
      )}

      <div className="steps">
        <span className={step === 1 ? 'active' : ''}>1 · {t(lang, 'step1')}</span>
        <span className={step === 2 ? 'active' : ''}>2 · {t(lang, 'step2')}</span>
        <span className={step === 3 ? 'active' : ''}>3 · {t(lang, 'step3')}</span>
      </div>

      {error && <div className="error">{error}</div>}

      {step === 1 && (
        <div className="card">
          {cfg.items.map((item) => (
            <div className="item-row" key={item.id}>
              <div>
                <strong>{item.name}</strong>
                <div className="muted">
                  {item.packSize} · {formatINR(item.pricePaise)}
                </div>
              </div>
              <input
                className="qty"
                type="number"
                min={0}
                max={20}
                placeholder={t(lang, 'quantity')}
                value={qty[item.id] ?? 0}
                onChange={(e) =>
                  setQty((q) => ({ ...q, [item.id]: Math.max(0, Number(e.target.value) || 0) }))
                }
              />
            </div>
          ))}
          <div className="total-bar">
            <div>
              <strong>{packets}</strong> {t(lang, 'packets')} · <strong>{(preview?.weightKg ?? 0).toFixed(2)}</strong>{' '}
              {t(lang, 'kg')} · <strong>{formatINR(amount)}</strong>
            </div>
            <button className="btn block" disabled={packets < 1} onClick={() => setStep(2)}>
              {t(lang, 'next')}
            </button>
          </div>
        </div>
      )}

      {step === 2 && (
        <div className="card">
          <label>{t(lang, 'name')}</label>
          <input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
          <label>{t(lang, 'mobile')}</label>
          <input
            value={form.mobile}
            onChange={(e) => setForm({ ...form, mobile: e.target.value })}
            inputMode="numeric"
          />
          <div className="row">
            <button className="btn secondary" onClick={sendOtp} type="button">
              {t(lang, 'requestOtp')}
            </button>
          </div>
          {otpSent && (
            <>
              <label>{t(lang, 'otp')}</label>
              <input
                value={form.otp}
                onChange={(e) => setForm({ ...form, otp: e.target.value })}
                inputMode="numeric"
              />
              {otpExpected && <p className="muted">Dev OTP: {otpExpected}</p>}
            </>
          )}
          <label>{t(lang, 'email')}</label>
          <input
            type="email"
            value={form.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })}
          />
          <label>{t(lang, 'address')}</label>
          <textarea
            rows={3}
            value={form.address}
            onChange={(e) => setForm({ ...form, address: e.target.value })}
          />
          <label>{t(lang, 'pin')}</label>
          <input
            value={form.pinCode}
            onChange={(e) => setForm({ ...form, pinCode: e.target.value })}
            inputMode="numeric"
          />
          <div className="row" style={{ marginTop: 12 }}>
            <button className="btn secondary" onClick={() => setStep(1)}>
              {t(lang, 'back')}
            </button>
            <button className="btn" onClick={() => setStep(3)}>
              {t(lang, 'next')}
            </button>
          </div>
        </div>
      )}

      {step === 3 && (
        <div className="card">
          <h2>{t(lang, 'beforeYouPay')}</h2>
          <div className="terms-box">{cfg.terms}</div>
          <p className="muted">{cfg.privacyNotice}</p>
          <label className="row" style={{ marginTop: 8 }}>
            <input
              type="checkbox"
              checked={accepted}
              onChange={(e) => setAccepted(e.target.checked)}
              style={{ width: 24, minHeight: 24 }}
            />
            <span>{t(lang, 'acceptTerms')}</span>
          </label>
          <p>
            <strong>
              {packets} {t(lang, 'packets')} · {formatINR(amount)}
            </strong>
          </p>
          <button className="btn gold block" disabled={!accepted || loading} onClick={pay}>
            {t(lang, 'pay')}
          </button>
        </div>
      )}
    </main>
  );
}
