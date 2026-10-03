type Props = {
  step: number;
  labels: string[];
  onGo: (step: number) => void;
};

/** Three-dot progress indicator. Completed steps are tappable to go back; forward moves only via Next. */
export function Stepper({ step, labels, onGo }: Props) {
  return (
    <ol className="stepper no-print" aria-label="Progress">
      {labels.map((label, i) => {
        const n = i + 1;
        const state = n < step ? 'done' : n === step ? 'active' : 'todo';
        return (
          <li key={n} className={state} aria-current={n === step ? 'step' : undefined}>
            <button type="button" disabled={state !== 'done'} onClick={() => onGo(n)}>
              <span className="dot" aria-hidden="true">{state === 'done' ? '✓' : n}</span>
              <span className="lbl">{label}</span>
            </button>
          </li>
        );
      })}
    </ol>
  );
}
