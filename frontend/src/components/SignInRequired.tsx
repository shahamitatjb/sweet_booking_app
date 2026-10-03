import { AppHeader } from './AppHeader';

export function SignInRequired() {
  return (
    <>
      <AppHeader title="Committee" backHref="/" />
      <main className="container">
        <div className="card">
          <h2>Not signed in</h2>
          <p className="muted">Sign in with your committee Google account to continue.</p>
          <a className="btn block" href="/staff/login">
            Staff login
          </a>
        </div>
      </main>
    </>
  );
}
