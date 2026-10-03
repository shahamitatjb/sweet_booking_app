'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { t, type Lang } from '../i18n/dicts';
import { api } from '../lib/format';
import { AppHeader } from '../components/AppHeader';
import { Diya } from '../components/Diya';

type Config = { title: string; subtitle: string; notice?: string; bookingEnabled?: boolean };

const FALLBACK: Config = { title: 'Diwali Sweets Booking', subtitle: 'Community Trust — Pune' };

export default function HomePage() {
  const [lang, setLang] = useState<Lang>('en');
  const [cfg, setCfg] = useState<Config>(FALLBACK);

  useEffect(() => {
    setLang((localStorage.getItem('jb_lang') as Lang) || 'en');
  }, []);

  useEffect(() => {
    api<Config>(`/api/public/config?lang=${lang}`)
      .then((c) => setCfg({ title: c.title, subtitle: c.subtitle, notice: c.notice, bookingEnabled: c.bookingEnabled }))
      .catch(() => undefined);
  }, [lang]);

  useEffect(() => {
    document.title = cfg.title;
  }, [cfg]);

  function setLangAndSave(l: Lang) {
    setLang(l);
    localStorage.setItem('jb_lang', l);
  }

  return (
    <>
      <AppHeader title={cfg.title} subtitle={cfg.subtitle} lang={lang} onLang={setLangAndSave} />
      <main className="container">
        <div className="card hero">
          <Diya size={64} />
          <h1>{cfg.title}</h1>
          <p className="muted">{t(lang, 'pickupOnly')}</p>
          {cfg.notice && <p>{cfg.notice}</p>}
          {cfg.bookingEnabled === false && <div className="card error flat">{t(lang, 'bookingClosed')}</div>}
          <div className="cta">
            <Link className="btn gold block lg" href="/book">
              {t(lang, 'startBooking')}
            </Link>
          </div>
        </div>
        <p className="muted small" style={{ textAlign: 'center' }}>
          {t(lang, 'verifyNote')}
        </p>
        <p className="page-footer">
          <Link href="/staff/login">{t(lang, 'staffLoginLink')}</Link>
        </p>
      </main>
    </>
  );
}
