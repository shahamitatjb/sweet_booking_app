import { clampQty } from '../lib/validators';

type Props = {
  value: number;
  max: number;
  label: string;
  onChange: (next: number) => void;
};

/** Large minus / plus controls around a numeric input, sized for thumbs. */
export function QtyStepper({ value, max, label, onChange }: Props) {
  return (
    <div className="qty-stepper" role="group" aria-label={label}>
      <button type="button" aria-label={`Remove one ${label}`} disabled={value <= 0} onClick={() => onChange(clampQty(value - 1, max))}>
        −
      </button>
      <input
        inputMode="numeric"
        pattern="[0-9]*"
        aria-label={`${label} packets`}
        value={value}
        onFocus={(e) => e.target.select()}
        onChange={(e) => onChange(clampQty(Number(e.target.value), max))}
      />
      <button type="button" aria-label={`Add one ${label}`} disabled={value >= max} onClick={() => onChange(clampQty(value + 1, max))}>
        +
      </button>
    </div>
  );
}
