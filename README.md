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

## Demo on Render free

1. Push this repo to GitHub.
2. Create a Render account → **New + Blueprint** → select repo → uses `render.yaml`.
3. Create **Neon** free project; copy the Postgres URL.
4. Set env vars on `jb-api` (see `.env.example`). Minimum for demo:
   - `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
   - `OTP_PROVIDER=dev`, `EMAIL_PROVIDER=console`
   - `JWT_SECRET`, `QR_HMAC_SECRET` (long random strings)
5. Deploy. First deploy is slow (Maven + Node build).
6. After deploy, set `API_PROXY_TARGET` on `jb-frontend` to the **internal** `jb-api` URL Render shows (e.g. `http://jb-api-xxxx:8080`), or the public API URL if rewrites need it.

**Free-tier caveats (important):** services sleep after 15 min; Spring Boot cold start can be 30s–2min; free Render Postgres expires in 30 days — use **Neon**, not free Render Postgres, for any data you care about.

## Production (booking window)

| Piece | Action |
|---|---|
| Render | Upgrade `jb-frontend` and `jb-api` to **Starter** (min) or **Standard 1C-2G** for comfort |
| Neon | Upgrade to Launch/pay-as-you-go; enable backups |
| Razorpay | Trust merchant account + live keys; webhook URL `https://<api>/api/webhooks/razorpay` |
| OTP | Set `OTP_PROVIDER=sms` only after DLT + provider live; until then **email OTP** (admin setting `otp_provider`) |
| Email | Resend (or similar) + real from-domain; set `RECEIPT_CC_EMAILS` to Amit Shah + Shailesh bhai |
| Google | OAuth client; add staff emails via Admin UI or `database/seed.sql` |
| Catalogue | Admin confirms item list; set booking window; fill Hindi/Gujarati texts |
| Domain | Optional later; needed for professional email deliverability |

## Functional notes (from spec + interview decisions)

- **Booking IDs:** `JB-0001…` assigned **only when paid** (gapless counter row + `FOR UPDATE` + unique constraints).
- **Payments:** Razorpay hosted checkout; counter **cash** and **staff-confirmed UPI** (no dynamic QR in v1 — authenticity weaker; audited).
- **WhatsApp:** deferred; **email** to customer (if provided) + configured trustee alert emails + receipt PDF CC list.
- **OTP:** provider-pluggable; production blocked for SMS until DLT; email OTP interim allowed via settings.
- **Receipts:** immutable; print CSS `@page { size: 14.9cm 21cm; margin: 0 }`; QR HMAC verification at `/v/...`.
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
