'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import type { Lang } from '../i18n/dicts';
import { api } from '../lib/format';

type Config = {
  title: string;
  subtitle: string;
};

const FALLBACK: Config = {
  title: 'Diwali Sweets Booking',
  subtitle: 'Community Trust — Pune · Pickup only',
};

export default function HomePage() {
  const [cfg, setCfg] = useState<Config>(FALLBACK);

  useEffect(() => {
    const lang = ((localStorage.getItem('jb_lang') as Lang) || 'en') as Lang;
    api<Config>(`/api/public/config?lang=${lang}`)
      .then((c) => setCfg({ title: c.title, subtitle: c.subtitle }))
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    document.title = cfg.title;
  }, [cfg]);

  return (
    <main className="container">
      <div className="card">
        <h1>{cfg.title}</h1>
        <p className="muted">{cfg.subtitle}</p>
        <p>
          <Link className="btn block" href="/book">
            Start booking
          </Link>
        </p>
        <p className="muted">Receipt verification is available via the QR code on your receipt.</p>
      </div>
      <p className="page-footer">
        <Link href="/staff/login">Staff login</Link>
      </p>
    </main>
  );
}
