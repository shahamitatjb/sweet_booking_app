# Diwali Sweets Booking (Mithai)

Mobile-first booking app for a Pune community trust: online + counter bookings, Razorpay payments, verifiable receipts, admin dashboard, Excel export.

**Stack (decided):** Next.js (UI) + Spring Boot (API) + PostgreSQL on **Neon**, deployed on **Render** (free for demo → paid during booking window).

## Repo layout

```
frontend/     Next.js 14 (App Router) — public booking, staff, admin, print CSS
backend/      Spring Boot 3 / Java 21 — API, auth, payments, jobs, email outbox
database/     schema.sql + seed.sql (local Docker / Neon)
render.yaml   Render Blueprint (two free web services for demo)
docker-compose.yml  local Postgres + API + web
```

## Quick start (local)

```bash
# 1) Database + seed
docker compose up -d db

# 2) API (needs JDK 21 + Maven)
cd backend
# if no mvnw yet: mvn -q -DskipTests package
# Run with env from ../.env.example
export DATABASE_URL=jdbc:postgresql://localhost:5432/mithai
export DATABASE_USERNAME=jb
export DATABASE_PASSWORD=jb
export OTP_PROVIDER=dev
export EMAIL_PROVIDER=console
export FRONTEND_ORIGIN=http://localhost:3000
mvn spring-boot:run

# 3) Web
cd ../frontend
npm install
API_PROXY_TARGET=http://localhost:8080 npm run dev
```

Open http://localhost:3000

## Deploy on Render (free tier for testing)

1. Push this repo to GitHub.
2. Create a **Neon** project (free). Note host, database, user and password.
3. Render → **New + Blueprint** → select the repo. `render.yaml` creates `jb-frontend` and `jb-api`.
4. Fill the `sync: false` env vars on `jb-api`: `DATABASE_URL` (`jdbc:postgresql://<host>/<db>?sslmode=require`),
   `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `BOOTSTRAP_ADMIN_EMAILS`, `FRONTEND_ORIGIN`
   (`https://<jb-frontend host>`), `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `EMAIL_FROM`, `RESEND_API_KEY`,
   `RECEIPT_CC_EMAILS`. On `jb-frontend` set `API_PROXY_TARGET` to the `jb-api` URL.
5. Google Cloud Console → the OAuth client → add the redirect URI
   `https://<jb-frontend host>/login/oauth2/code/google`. Locally the equivalent is
   `http://localhost:3000/login/oauth2/code/google` (the callback goes through the Next proxy).
6. Deploy. Flyway applies `V1__schema.sql` to the empty Neon database and the bootstrap admins are
   inserted. Sign in with one of them, add staff and items, set the booking window and texts.

**Free-tier caveats:** services sleep after 15 min; Spring Boot cold start can be 30s–2min; free Render
Postgres expires in 30 days, which is why Neon is used.

## Production (booking window)

| Piece | Action |
|---|---|
| Render | Upgrade `jb-frontend` and `jb-api` to **Starter** (min) or **Standard 1C-2G** for comfort |
| Neon | Upgrade to Launch/pay-as-you-go; enable backups |
| Razorpay | Trust merchant account + live keys; webhook URL `https://<api>/api/webhooks/razorpay` |
| OTP | Set `OTP_PROVIDER=sms` only after DLT + provider live; until then **email OTP** (admin setting `otp_provider`) |
| Email | Resend (or similar) + real from-domain; set `RECEIPT_CC_EMAILS` to Amit Shah + Shailesh bhai |
| Admins | `BOOTSTRAP_ADMIN_EMAILS` only seeds an empty staff table; manage the rest in Admin → Settings |
| Google | OAuth client; add staff emails via Admin UI or `database/seed.sql` |
| Catalogue | Admin confirms item list; set booking window; fill Hindi/Gujarati texts |
| Domain | Optional later; needed for professional email deliverability |

## Functional notes (from spec + interview decisions)

- **Booking IDs:** `JB-0001…` assigned **only when paid** (gapless counter row + `FOR UPDATE` + unique constraints).
- **Payments:** Razorpay hosted checkout; counter **cash** and **staff-confirmed UPI** (no dynamic QR in v1 — authenticity weaker; audited).
- **WhatsApp:** deferred; **email** to customer (if provided) + configured trustee alert emails + receipt PDF CC list.
- **OTP:** provider-pluggable; production blocked for SMS until DLT; email OTP interim allowed via settings.
- **Receipts:** immutable; print CSS `@page { size: 14.9cm 21cm; margin: 0 }`; signed QR image + verification at `/v/...`.
- **Area:** no pin-code restriction; collection is from a fixed venue (set in the notice/terms texts).
- **Export:** admin Excel with blank **Handed over** column.
- **Audit:** append-only table (UPDATE/DELETE blocked in DB).
- **PII:** retention “keep indefinitely” (trust decision) — revisit with legal for DPDP.

## Go-live checklist

- [ ] Trust Razorpay merchant (not personal) + webhook secret
- [ ] DLT SMS templates + OTP provider OR documented email-OTP interim
- [ ] Email sending identity + deliverability test
- [ ] Staff Google emails loaded; 2FA recommended on their Google accounts
- [ ] Items confirmed; booking window set; terms in EN (then HI/GU)
- [ ] `RECEIPT_CC_EMAILS` set
- [ ] Paid Render + Neon; backup restore tested
- [ ] Load check: 200 parallel finalise (test) / 100 concurrent users
- [ ] Print test on real 14.9×21 paper
- [ ] Privacy notice accepted on booking form

## Seed staff (replace before go-live)

| Email | Role |
|---|---|
| admin1@example.com | ADMIN |
| admin2@example.com | ADMIN |
| counter1@example.com | COUNTER |
| counter2@example.com | COUNTER |

## Open items still needed from the trust

1. Real staff Google emails  
2. Final items/prices/weights (staging samples are in `database/seed.sql`)  
3. Booking window + pickup dates and terms wording (HI/GU)  
4. Email addresses for Amit Shah and Shailesh bhai  
5. Trust name for default title/subtitle  
6. Razorpay trust KYC status  
7. OTP provider / DLT status  
