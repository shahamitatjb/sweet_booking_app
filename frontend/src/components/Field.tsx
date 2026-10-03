import type { ReactNode } from 'react';

type Props = {
  id: string;
  label: string;
  optional?: string;
  hint?: string;
  error?: string | null;
  children: ReactNode;
};

/** Label, control and a single message slot: the error when there is one, otherwise the hint. */
export function Field({ id, label, optional, hint, error, children }: Props) {
  return (
    <div className={`field${error ? ' invalid' : ''}`}>
      <label htmlFor={id}>
        {label} {optional && <span className="opt">({optional})</span>}
      </label>
      {children}
      {error ? (
        <p className="field-error" id={`${id}-error`} role="alert">
          {error}
        </p>
      ) : hint ? (
        <p className="field-hint">{hint}</p>
      ) : null}
    </div>
  );
}
