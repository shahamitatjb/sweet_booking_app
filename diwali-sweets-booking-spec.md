# Diwali Sweets Booking App: Functional and Technical Specification

Version 1.0 · Status: draft for development team review
Reference: the clickable prototype (same screens and flows, sample data, no real payments, no real backend).

---

## 1. Purpose and scope

A mobile-friendly website for a community trust in Pune to take Diwali sweet bookings, collect payment securely, issue verifiable receipts, and give committee members the data they need to hand over sweets at pickup.

**In scope**
- Public online booking with OTP verification and online payment.
- Counter booking by logged-in committee members (cash or UPI).
- Admin dashboard, bookings table, receipt printing, Excel export.
- Admin-configurable items, prices, texts and WhatsApp alert numbers.
- Audit log of all staff actions.
- English, Hindi and Gujarati for public pages.

**Out of scope (v1)**
- Home delivery, delivery charges, vehicle planning (handover is pickup only; delivery of parcels is outside the app).
- Cancellations and refunds by users (policy: none). Only automatic refunds for technical payment failures.
- Offline or bulk back-entry of bookings.
- Native mobile apps.
- Bucketing of bookings (managed manually from booking IDs).

## 2. Users and roles

| Role | Who | Access |
|---|---|---|
| Customer | Anyone in Pune or PCMC | Public booking pages only. No account. |
| Counter | Committee member (e.g. at registration table) | Google login. Counter booking, own bookings, print own receipts. |
| Admin | Senior committee members | Google login. Everything: dashboard, all bookings, settings, audit log, counter booking. |

Initial staff list: two admins and two counter users, added by Google email by an admin. Only emails on the list can sign in.

## 3. Functional requirements

### 3.1 Public booking (customer)
1. **Language** selector: English, Hindi, Gujarati. Fixed labels are translated; admin-configured texts show as entered (see 3.7).
2. **Step 1, items:** list of active items with pack size and price. Customer enters packet quantity per item. At least one packet is required. Running total shows packets, kg and amount.
3. **Step 2, details:** full name, mobile number with OTP verification, full address (for records), pin code. Pin code must be in Pune or PCMC (411001 to 411062; the allowed list is configurable). Details persist when moving back and forth between steps.
4. **Step 3, review and pay:** order summary and a "Before you pay" box showing the admin-configured terms (pickup dates and time, parcel service note, not responsible after prescribed dates, no cancellations, no refunds, automatic refund of failed payments). The customer must tick acceptance before paying.
5. **Payment** through the payment gateway hosted checkout (see 4.5).
6. **Confirmation:** only after the gateway confirms payment, the app issues the booking ID and receipt page with the thank-you message. The receipt is also sent as PDF by SMS and WhatsApp to the customer.
7. **Booking window:** admin sets start and end date/time and a manual on/off switch. When closed, customers see a closed message.
8. Timestamp of booking is recorded and shown on the receipt.

### 3.2 Booking ID
- Format `JB-0001`, `JB-0002`, … (prefix `JB-`, zero-padded to 4 digits, extends to 5 digits after 9999).
- One running sequence shared by online and counter bookings.
- IDs are never reused and never duplicated. See 4.4 for the concurrency design.

### 3.3 Counter booking (staff)
1. Staff signs in with Google.
2. Form: customer name, mobile, item quantities, address, pin code (same validation), **payment method: Cash or UPI**.
3. Review screen shows the amount due.
4. **Cash:** staff confirms exact cash received, then the receipt is issued.
5. **UPI:** the app shows a payment QR that is unique to this booking and carries the exact amount (gateway dynamic QR). Receipt is issued only when the gateway confirms that payment. The shared static table QR must not be used because a payment cannot be tied to a booking and screenshots can be faked.
6. Booking records the staff member who took it and the payment method.
7. A printable receipt is available immediately.

### 3.4 Admin dashboard and bookings
- **Dashboard:** total bookings, total quantity (packets and kg), total collected; split by channel (Online, Counter cash, Counter UPI); packets per item (to prepare).
- **Bookings table:** ID, booked-at timestamp, name, mobile, address, items, quantity, channel and payment mode, amount. Header **select-all checkbox**, row checkboxes, select all / clear buttons. Sortable by ID and time; searchable by name, mobile, ID.
- **Print receipts:** for one or many selected bookings. Paper size **14.9 cm wide × 21 cm high**, one receipt per page (CSS `@page { size: 14.9cm 21cm; margin: 0 }`).
- **Export to Excel (.xlsx):** columns: Booking ID, Booked at (IST), Name, Mobile, Address, Pin, one column per item (packets), Total packets, Total kg, Amount, Channel, Payment mode, Taken by, and a blank **Handed over** column for manual tick-off.
- **Collections tab (admin):** online payments vs gateway settlements vs bank credits; counter collections per user (cash to hand over, UPI via gateway). See 4.7.

### 3.5 Receipt
Content (screen, PDF and print): title, booking ID, booked-at time, QR code and verification code, customer name, mobile, address, item lines (item, pack size, packets, amount), total quantity and amount, payment mode and (for counter) who took it, configured terms, thank-you message.
The receipt is generated only for confirmed, paid bookings. Receipts are immutable. A mistake is handled by voiding the booking with a reason (admin only, audited), not by editing.

### 3.6 QR code (authenticity)
Each receipt carries a QR with a signed verification link. Scanning opens the verification page, which states whether the booking is genuine and paid. See section 4.6 for the mechanism.

### 3.7 Admin settings
- **Items:** name, pack size, price, packed weight (kg), active flag; add new items. A **Confirm item list** action locks the catalogue (who and when are recorded); **Unlock to edit** reopens it and is audited. Inactive items are hidden from booking forms. Prices are copied onto each booking at the time of booking.
- **Texts (configurable):** main title, subtitle, notice shown at the top of the booking form, terms shown before payment (one point per line), thank-you message (default "Thank you for your continued support"). Recommend storing one value per language so Hindi and Gujarati can be provided.
- **Booking control:** on/off switch, start and end date/time.
- **WhatsApp alert numbers:** add and remove the numbers that receive a message for every booking (see 3.8).
- **Staff access:** add or remove Google emails, assign Admin or Counter role.

### 3.8 WhatsApp alerts to the committee
- On every confirmed booking (online or counter), the app sends a WhatsApp message to every configured number.
- Message content (template): booking ID, customer name, packets and kg, amount, channel and payment mode, booked-at time.
- Numbers: 10-digit Indian mobiles stored in E.164 (`+91…`). Duplicates rejected. Admin can send a test message.
- A failure to send never blocks or fails the booking. See 4.8.

### 3.9 Audit log
Every action by an admin or counter user is recorded, with who, role, time, action and details. Entries cannot be edited or deleted. Admin-only view with filters and export.

Audited actions include: sign-in, denied sign-in, sign-out; counter booking issued (ID, amount, method); online booking paid; receipt print preview/print; Excel export; item add, edit, confirm, unlock; text changes (which fields changed); booking on/off and date changes; staff added or removed; WhatsApp numbers added or removed; booking voided.

### 3.10 Validation summary
| Field | Rule |
|---|---|
| Name | Required, 3 or more characters |
| Mobile | 10 digits, starts 6 to 9, OTP verified (online) |
| Address | Required, 10 or more characters |
| Pin code | 6 digits, within the allowed Pune/PCMC list |
| Quantity | Whole numbers, at least one packet overall, upper limit configurable |
| Terms | Must be accepted before payment |

### 3.11 Non-functional requirements
- Capacity: 2,000 bookings over the booking period; peak of about 50 concurrent users. Design for ten times that headroom.
- Availability target: 99.5% during the booking window; planned maintenance outside it.
- Mobile-first, works on low-end Android browsers; pages light (target under 300 KB initial).
- Time zone: store UTC, display IST.
- Accessibility: readable fonts including Devanagari and Gujarati, sufficient contrast, tap targets of at least 44 px.

## 4. Technical specification

### 4.1 Architecture
- Web front end (responsive) and an API backend.
- Relational database (PostgreSQL recommended) as the single source of truth.
- Background worker for notifications, payment reconciliation and retries.
- External services: payment gateway, OTP/SMS provider, WhatsApp Business provider, Google Sign-In.

Suggested stack (open to team preference): Next.js or a Node.js API with TypeScript, PostgreSQL (managed, with daily backups and point-in-time recovery), a job queue (Postgres-based or Redis), object storage for receipt PDFs. Managed hosting with HTTPS and automatic certificates.

### 4.2 Data model (main tables)
- `items`: id, name (per language), pack_size, price (integer paise), weight_kg, active, sort_order.
- `catalogue_confirmations`: id, confirmed_by, confirmed_at, snapshot_json.
- `orders`: id (UUID), status (`created`, `awaiting_payment`, `paid`, `failed`, `voided`), channel (`online`, `counter`), customer name, mobile, address, pin, total_amount, gateway_order_id, created_at, created_by (staff id for counter), accepted_terms_at.
- `order_items`: order_id, item_id, item_name, pack_size, unit_price, quantity (price and name copied at booking time).
- `payments`: id, order_id, method (`gateway`, `cash`), gateway_payment_id (unique), amount, status, captured_at, settlement_id, fee, tax, refund_id.
- `bookings`: booking_no (integer, unique), booking_id text (`JB-0001`, unique), order_id (unique), confirmed_at.
- `counters`: name (`booking`), value. One row, used only for ID allocation.
- `staff`: id, email (unique), name, role, active.
- `notification_outbox`: id, booking_id, to_number, template, payload, status, attempts, next_attempt_at, provider_message_id, last_error.
- `settings`: key, language, value (title, subtitle, notice, terms, thank-you, booking window, allowed pin list).
- `audit_log`: id, at, actor_id or label, role, action, details_json, ip, request_id. Append-only.
- `cash_handovers` (recommended): staff id, date, expected cash, handed over, received by.

Money is stored as integer paise. All timestamps are UTC.

### 4.3 Booking flow (online)
1. Browser sends items, quantities and details. The **server** recomputes prices from the database; it never trusts a price from the browser.
2. The server creates an `order` (`awaiting_payment`) and a gateway order for the exact amount.
3. The customer pays on the gateway's hosted checkout. Card and UPI details never reach our servers.
4. The gateway calls our **webhook**. The server verifies the signature, checks amount and order, then runs the idempotent **finalise booking** routine (4.4).
5. The browser is redirected back and polls the booking status. The receipt page appears only once the booking is `paid`.
6. A reconciliation job re-checks any order stuck in `awaiting_payment`. If a payment was captured but no booking exists and cannot be finalised, the payment is **refunded automatically**.

### 4.4 Booking ID generation and concurrency
**Requirement:** under many simultaneous bookings, every confirmed booking gets a unique ID, in order, without gaps or duplicates.

**Design**
- **IDs are assigned only when payment is confirmed**, not when the form is submitted. Abandoned or failed attempts have an internal UUID but never consume a `JB-` number, so there are no gaps from unpaid attempts.
- Allocation happens inside one database transaction in the *finalise booking* routine:
  1. `SELECT … FROM orders WHERE id = $1 FOR UPDATE` (lock the order, so two callers cannot finalise it twice).
  2. If the order is already `paid` and has a booking, return it (idempotent).
  3. `UPDATE counters SET value = value + 1 WHERE name = 'booking' RETURNING value;` This takes a row lock, so concurrent transactions queue up and each receives a different number.
  4. Insert the booking with `booking_no = value` and `booking_id = 'JB-' || lpad(value::text, 4, '0')`, mark the order `paid`, insert the audit entry and the notification outbox rows.
  5. Commit. If anything fails, the transaction rolls back, the counter increment rolls back too, and **no number is lost**.
- **Safety nets:** unique constraints on `bookings.booking_no`, `bookings.booking_id`, `bookings.order_id` and `payments.gateway_payment_id`. A duplicate can never be stored even if application code had a bug.
- **Idempotency:** the webhook and the browser redirect can both try to finalise the same order, and gateways retry webhooks. Step 2 above makes repeated calls safe.
- **Why not a plain database sequence?** Sequences are fast but do not roll back, so a failed transaction leaves a gap. The counter-row approach is gapless. At roughly 2,000 bookings the row lock is not a bottleneck (each transaction holds it for a few milliseconds).
- **Counter bookings** use the same routine after the cash confirmation or the gateway's UPI confirmation.
- **Testing:** a test that fires 200 parallel finalise calls (including duplicates for the same order) must produce exactly the right count of bookings, consecutive IDs and no duplicates.

### 4.5 Payments
- Use a payment gateway with hosted checkout, UPI and cards (for example Razorpay, Cashfree or PayU). The trust needs onboarding with PAN, registration certificate and bank account. Start early, since approval is the longest lead-time item.
- Verify webhook signatures (HMAC with the webhook secret) and reject unsigned or stale requests. Compare gateway amount and order ID with our order.
- Counter UPI uses a gateway **dynamic QR** (or payment link) created per booking with a fixed amount, so payment maps to exactly one booking.
- Cash is recorded by the counter user and appears in that user's daily cash total.
- Automatic refund for: captured payment with no confirmed booking, duplicate payment for the same order, amount mismatch.
- No user-initiated cancellation or refund.
- Store gateway payment ID, settlement ID and fees for reconciliation.

### 4.6 QR code: how it provides security and authenticity
**What it encodes:** a URL such as `https://<domain>/v/JB-0123.<signature>`.
`signature = base64url( first 16 bytes of HMAC-SHA256( secret_key, "JB-0123|<amount>|<confirmed_at_epoch>|<key_id>" ) )`.

**What happens on scan:** the server looks up the booking, recomputes the HMAC from its own records and compares in constant time. It shows **Genuine, paid** only if the signature is valid and the booking exists and is paid. Otherwise it shows **Invalid receipt**.

**What it protects against**
- **Forged or invented receipts:** sequential IDs are easy to guess, but a valid signature cannot be produced without the server's secret key.
- **Altered receipts:** changing the ID, name or quantity on a genuine receipt breaks the match, because the verification page reads values from the database, not from the paper.
- **Receipts for unpaid bookings:** only paid, confirmed bookings have a valid record.
- **Tampering offline:** the signature is checked server-side, so editing the printed QR or the code makes verification fail.

**What it does not stop on its own, and mitigations**
- **A genuine receipt photographed or shared.** Mitigations: staff compare the name and the last four digits of the mobile number on screen with the person; the verification page shows the time of the first scan and warns on repeated scans; the Excel "Handed over" column is ticked once per booking.
- **Key leakage.** Mitigation: the secret is held only in server configuration, never in the browser, rotated if exposed (the key ID in the token supports rotation without invalidating old receipts).

**Privacy of the verification page:** unauthenticated visitors see only status (genuine/invalid), booking ID and total packets. Full details (name, mobile, address) show only to a signed-in staff member.
**Implementation notes:** version the token format, rate-limit the verification endpoint, log failed verifications, generate the QR server-side (error correction level M or higher) and place it at least 3 cm square on the 14.9 × 21 cm print.

### 4.7 Reconciliation
- Per booking: gateway order ID, payment ID, settlement ID and UTR once available.
- Daily job fetches the gateway's settlement report (API or CSV import) and matches payments to bookings. The admin screen shows paid, awaiting settlement, settled, refunded, plus mismatches (payment without booking, booking without payment, amount differences, unexplained bank credit).
- Expected bank credit = gross − gateway fee − tax. Compare with the actual bank credit amount entered or imported.
- Counter cash: per user per day, cash taken versus cash handed over.

### 4.8 WhatsApp alerts
- Use the WhatsApp Business Platform (Cloud API or an approved provider). Alerts are sent with a pre-approved **message template**. Recipients must be opted-in and the account needs a verified business profile.
- Implementation: the booking transaction writes one `notification_outbox` row per configured number. A worker sends them, with retries (exponential backoff), records the provider message ID, and marks `sent`, `delivered` or `failed`. Webhooks from the provider update delivery status.
- Failures never roll back or delay the booking. Persistent failures show on the admin page and in the audit log.
- Customer receipts (PDF by WhatsApp and SMS) use the same outbox and worker.
- Configuration of numbers is admin-only and audited. Numbers are masked in logs.

### 4.9 Authentication and authorisation
- **Customers:** mobile OTP, 6 digits, expires in 5 minutes, limited attempts, rate-limited per mobile number and per IP; bot protection (CAPTCHA) on OTP requests. Indian SMS needs DLT registration of sender ID and templates (allow lead time).
- **Staff:** Google Sign-In (OAuth 2.0 / OIDC). Accept only a **verified email that is on the staff allowlist**; map to role. Short session lifetime, secure cookies (`HttpOnly`, `Secure`, `SameSite`), sign-out on role removal. Recommend 2-step verification on staff Google accounts.
- **Authorisation:** enforced on the server for every endpoint (not only in the UI). Counter users can only create bookings and read their own; admin endpoints require the Admin role.

### 4.10 Security and privacy
- HTTPS only, HSTS, a strict content security policy, secrets in environment variables or a secrets manager.
- Server-side validation of every input; parameterised queries; output escaping; CSRF protection for state-changing calls.
- Rate limiting and basic abuse protection on all public endpoints.
- Audit log table is append-only (database permissions deny update and delete; optionally chain entries with a hash).
- Personal data (name, mobile, address) minimised, encrypted at rest by the database provider, access limited to staff roles. Define a retention period and deletion after the festival season.
- Comply with India's Digital Personal Data Protection Act, 2023: show a short privacy notice and obtain consent at booking. The trust should confirm obligations with its legal adviser.
- Payments keep the trust outside PCI scope by using hosted checkout.
- Backups: automated daily, restore tested before launch.

### 4.11 Printing and export
- Receipt print view: HTML/CSS with `@page { size: 14.9cm 21cm; margin: 0 }`, one receipt per `.page` with `break-after: page`, black on white, embedded fonts covering English, Hindi, Gujarati. Verify on the actual printer and paper.
- PDF of the same layout for WhatsApp/SMS and download.
- Excel via a server-side `.xlsx` library, UTF-8 safe for Hindi and Gujarati.

### 4.12 Internationalisation
- UI strings in English, Hindi, Gujarati resource files; user choice is remembered.
- Admin-configurable texts: one field per language with fallback to English.
- Currency: INR, formatted in the Indian digit grouping.

### 4.13 Environments and deployment
- Environments: local, staging (gateway test mode, test WhatsApp numbers), production.
- CI runs tests and migrations on each change; production deploys are manual approvals.
- Monitoring: uptime check, error tracking, alerts for webhook failures, outbox backlog, payment/booking mismatches.
- Go-live checklist: gateway live keys, webhook URL and secret, DLT templates approved, WhatsApp templates approved, domain and certificate, backup restore tested, staff accounts loaded, items confirmed, booking window set.

### 4.14 Testing and acceptance
| Area | Must-pass check |
|---|---|
| IDs | 200 parallel confirmations (with duplicate webhooks) give consecutive IDs, no duplicates |
| Payment | Paid → booking and receipt; failed or abandoned → none; captured without booking → auto refund |
| Webhook | Bad signature rejected; replays harmless |
| Counter | Cash and UPI flows; receipt only after confirmation |
| QR | Genuine verifies; altered or forged code fails |
| Roles | Counter cannot reach admin data; unlisted Google account denied |
| Audit | Every audited action creates an entry; no edit or delete possible |
| Print | 14.9 × 21 cm, multi-select, correct fonts on the real printer |
| Export | All columns present, Hindi/Gujarati text intact |
| WhatsApp | All configured numbers receive a message; failure does not affect booking |
| Load | 2,000 bookings and 100 concurrent users without errors |
| Devices | Low-end Android, iOS Safari, desktop Chrome |

## 5. Prototype versus real build
The prototype is a design reference only. It has no real payments, OTP, Google login, WhatsApp, PDF, Excel export or permanent storage, and data resets on reload. In the prototype, changing a price also changes the shown amount on past bookings; the real build copies prices onto each booking.

## 6. Open decisions
1. Payment gateway choice and onboarding status; convenience fee or absorbed fee.
2. WhatsApp provider and template approval; OTP/SMS provider and DLT registration.
3. Final item list, pack sizes, prices, packed weights; any stock limits or bulk discounts.
4. Booking window dates; pickup dates and time; exact wording of terms in all three languages.
5. Domain name and hosting; who maintains it after Diwali.
6. Real Google emails for staff and roles.
7. Data retention period after the event.
8. Whether receipts PDFs are also emailed (optional email field).
