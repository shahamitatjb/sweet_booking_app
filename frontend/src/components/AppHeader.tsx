import Link from 'next/link';
import type { ReactNode } from 'react';
import type { Lang } from '../i18n/dicts';
import { Diya } from './Diya';

type Props = {
  title: string;
  subtitle?: string;
  lang?: Lang;
  onLang?: (lang: Lang) => void;
  backHref?: string;
  wide?: boolean;
  children?: ReactNode;
};

const LANGS: Lang[] = ['en', 'hi', 'gu'];
const LANG_LABEL: Record<Lang, string> = { en: 'EN', hi: 'हिं', gu: 'ગુ' };

/** Festive maroon banner with the diya mark, title, optional language pills and back link. */
export function AppHeader({ title, subtitle, lang, onLang, backHref, wide, children }: Props) {
  return (
    <header className={`app-header no-print${wide ? ' wide' : ''}`}>
      <div className="inner">
        {backHref && (
          <Link className="back" href={backHref} aria-label="Back">
            ←
          </Link>
        )}
        <div className="brand">
          <Diya size={34} />
          <div style={{ minWidth: 0 }}>
            <h1>{title}</h1>
            {subtitle && <p className="sub">{subtitle}</p>}
          </div>
        </div>
        {lang && onLang && (
          <div className="lang-bar" role="group" aria-label="Language">
            {LANGS.map((l) => (
              <button key={l} type="button" className={lang === l ? 'active' : ''} onClick={() => onLang(l)} aria-pressed={lang === l}>
                {LANG_LABEL[l]}
              </button>
            ))}
          </div>
        )}
        {children}
      </div>
    </header>
  );
}
