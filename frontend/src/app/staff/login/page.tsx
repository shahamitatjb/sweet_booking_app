'use client';

export default function StaffLogin() {
  return (
    <main className="container">
      <div className="card">
        <h1>Staff login</h1>
        <p className="muted">Google sign-in for allowlisted committee emails only.</p>
        <a className="btn block" href="/oauth2/authorization/google">
          Continue with Google
        </a>
        <p className="muted" style={{ marginTop: 12 }}>
          Configure GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET on the API. Only emails in the staff table can sign in.
        </p>
      </div>
    </main>
  );
}
