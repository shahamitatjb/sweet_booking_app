'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState, type ReactNode } from 'react';
import { api } from '../lib/format';
import { canConfigure, canSeeDashboard } from '../lib/roles';
import { Diya } from './Diya';

export type Me = { email: string; name: string; role: string };

type Props = {
  active: 'admin' | 'counter' | 'settings';
  title?: string;
  me: Me | null;
  children: ReactNode;
};

const anyone = () => true;

const NAV = [
  { key: 'home', href: '/', label: 'Home', icon: '⌂', allowed: anyone, mobileOnly: true },
  { key: 'admin', href: '/admin', label: 'Dashboard', icon: '▦', allowed: canSeeDashboard, mobileOnly: false },
  { key: 'counter', href: '/staff/counter', label: 'Counter', icon: '🧾', allowed: anyone, mobileOnly: false },
  { key: 'settings', href: '/admin/settings', label: 'Settings', icon: '⚙', allowed: canConfigure, mobileOnly: false },
] as const;

/** Staff chrome: festive top bar with tabs on wide screens and a bottom tab bar on phones. */
export function AdminShell({ active, title = 'Committee', me, children }: Props) {
  const router = useRouter();
  const [signingOut, setSigningOut] = useState(false);
  const items = NAV.filter((n) => n.allowed(me?.role));

  async function signOut() {
    setSigningOut(true);
    try {
      await api('/api/staff/logout', { method: 'POST' });
    } finally {
      router.push('/');
    }
  }

  return (
    <>
      <header className="app-header admin-top no-print wide">
        <div className="inner">
          <Link href="/" className="brand brand-link" aria-label="Go to the public home page">
            <Diya size={30} />
            <h1>{title}</h1>
          </Link>
          <nav className="tabs" aria-label="Sections">
            {items
              .filter((n) => !n.mobileOnly)
              .map((n) => (
                <Link key={n.key} href={n.href} className={active === n.key ? 'active' : ''} aria-current={active === n.key ? 'page' : undefined}>
                  {n.label}
                </Link>
              ))}
          </nav>
          {me && (
            <div className="who">
              <strong>{me.name || me.email}</strong>
              <span className="role">{me.role}</span>
              <button type="button" className="signout" onClick={signOut} disabled={signingOut}>
                {signingOut ? 'Signing out…' : 'Sign out'}
              </button>
            </div>
          )}
        </div>
      </header>
      <main className="container wide">{children}</main>
      <nav className="tabbar no-print" aria-label="Sections">
        {items.map((n) => (
          <Link key={n.key} href={n.href} className={active === n.key ? 'active' : ''} aria-current={active === n.key ? 'page' : undefined}>
            <span className="ico" aria-hidden="true">{n.icon}</span>
            {n.label}
          </Link>
        ))}
      </nav>
    </>
  );
}
