import Link from 'next/link';

export default function HomePage() {
  return (
    <main className="container">
      <div className="card">
        <h1>Diwali Sweets Booking</h1>
        <p className="muted">Community Trust — Pune · Pickup only</p>
        <p>
          <Link className="btn block" href="/book">
            Start booking
          </Link>
        </p>
        <p>
          <Link className="btn secondary block" href="/staff/login">
            Staff login
          </Link>
        </p>
        <p className="muted">Receipt verification is available via the QR code on your receipt.</p>
      </div>
    </main>
  );
}
