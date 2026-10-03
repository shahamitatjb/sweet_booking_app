/** Small oil-lamp mark used in headers; purely decorative. */
export function Diya({ size = 28 }: { size?: number }) {
  return (
    <svg className="diya" width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" focusable="false">
      <path d="M32 4c6.5 8.5 10 14 10 20.5a10 10 0 0 1-20 0C22 18 25.5 12.5 32 4z" fill="#f4b23e" />
      <path d="M32 13c3.2 4.3 5 7.6 5 10.8a5 5 0 0 1-10 0c0-3.2 1.8-6.5 5-10.8z" fill="#fff1b8" />
      <path d="M6 38h52c0 11-11.6 20-26 20S6 49 6 38z" fill="#7a1f1f" />
      <ellipse cx="32" cy="38" rx="26" ry="5.5" fill="#9b2c2c" />
      <path d="M6 38h52" stroke="#c4922e" strokeWidth="3" strokeLinecap="round" />
      <path d="M14 48c4 4 10 6.5 18 6.5s14-2.5 18-6.5" stroke="#c4922e" strokeWidth="2" fill="none" strokeLinecap="round" />
    </svg>
  );
}
