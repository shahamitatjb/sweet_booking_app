-- Diwali Sweets Booking — PostgreSQL schema (Neon / local Postgres)
-- Money: integer paise. Timestamps: timestamptz (UTC).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── Catalogue ──────────────────────────────────────────────────────────
CREATE TABLE items (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name_en       TEXT NOT NULL,
  name_hi       TEXT,
  name_gu       TEXT,
  pack_size     TEXT NOT NULL,
  price_paise   INTEGER NOT NULL CHECK (price_paise >= 0),
  weight_kg     NUMERIC(6,3) NOT NULL CHECK (weight_kg >= 0),
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order    INTEGER NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalogue_confirmations (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  confirmed_by  TEXT NOT NULL,
  confirmed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  snapshot_json JSONB NOT NULL
);

-- ── Staff ──────────────────────────────────────────────────────────────
CREATE TABLE staff (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE,
  name          TEXT,
  role          TEXT NOT NULL CHECK (role IN ('ADMIN','COUNTER')),
  active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Orders / payments / bookings ───────────────────────────────────────
CREATE TABLE orders (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  status              TEXT NOT NULL DEFAULT 'created'
                      CHECK (status IN ('created','awaiting_payment','paid','failed','voided')),
  channel             TEXT NOT NULL CHECK (channel IN ('online','counter')),
  customer_name       TEXT NOT NULL,
  mobile              TEXT NOT NULL,
  address             TEXT NOT NULL,
  pin_code            TEXT NOT NULL,
  email               TEXT,
  total_amount        INTEGER NOT NULL CHECK (total_amount >= 0),
  total_packets       INTEGER NOT NULL DEFAULT 0,
  total_weight_kg     NUMERIC(8,3) NOT NULL DEFAULT 0,
  gateway_order_id    TEXT,
  payment_method      TEXT CHECK (payment_method IN ('gateway','cash','upi')),
  upi_reference       TEXT,
  created_by          BIGINT REFERENCES staff(id),
  accepted_terms_at   TIMESTAMPTZ,
  void_reason         TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);
CREATE INDEX idx_orders_mobile ON orders (mobile);

CREATE TABLE order_items (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id      UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  item_id       BIGINT REFERENCES items(id),
  item_name     TEXT NOT NULL,
  pack_size     TEXT NOT NULL,
  unit_price    INTEGER NOT NULL CHECK (unit_price >= 0),
  weight_kg     NUMERIC(6,3) NOT NULL DEFAULT 0,
  quantity      INTEGER NOT NULL CHECK (quantity > 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE payments (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_id              UUID NOT NULL REFERENCES orders(id),
  method                TEXT NOT NULL CHECK (method IN ('gateway','cash')),
  gateway_payment_id    TEXT UNIQUE,
  amount                INTEGER NOT NULL CHECK (amount >= 0),
  status                TEXT NOT NULL DEFAULT 'created'
                        CHECK (status IN ('created','captured','failed','refunded')),
  captured_at           TIMESTAMPTZ,
  settlement_id         TEXT,
  fee                   INTEGER,
  tax                   INTEGER,
  refund_id             TEXT,
  notes                 TEXT,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_order ON payments (order_id);

-- Gapless booking IDs: allocate only when paid (see counter row).
CREATE TABLE counters (
  name            TEXT PRIMARY KEY,
  value           BIGINT NOT NULL DEFAULT 0
);

INSERT INTO counters (name, value) VALUES ('booking', 0);

CREATE TABLE bookings (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  booking_no    BIGINT NOT NULL UNIQUE,
  booking_id    TEXT NOT NULL UNIQUE,          -- JB-0001
  order_id      UUID NOT NULL UNIQUE REFERENCES orders(id),
  confirmed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  qr_signature  TEXT NOT NULL,
  qr_key_id     TEXT NOT NULL DEFAULT 'k1',
  first_scanned_at TIMESTAMPTZ,
  scan_count    INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_bookings_confirmed ON bookings (confirmed_at DESC);

-- ── OTP / auth ─────────────────────────────────────────────────────────
CREATE TABLE otp_codes (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  channel       TEXT NOT NULL CHECK (channel IN ('sms','email')),
  destination   TEXT NOT NULL,
  code_hash     TEXT NOT NULL,
  attempts      INTEGER NOT NULL DEFAULT 0,
  expires_at    TIMESTAMPTZ NOT NULL,
  consumed_at   TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_otp_destination ON otp_codes (destination, created_at DESC);

-- Staff sessions / deny list optional; Google + allowlist checked in app.

-- ── Settings ───────────────────────────────────────────────────────────
CREATE TABLE settings (
  key             TEXT NOT NULL,
  language        TEXT NOT NULL DEFAULT 'en' CHECK (language IN ('en','hi','gu')),
  value           TEXT NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (key, language)
);

-- ── Outbox (email; WhatsApp deferred) ──────────────────────────────────
CREATE TABLE notification_outbox (
  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  booking_id          TEXT,
  order_id            UUID,
  kind                TEXT NOT NULL,          -- receipt_pdf | booking_alert
  to_address          TEXT NOT NULL,
  template            TEXT NOT NULL,
  payload             JSONB NOT NULL DEFAULT '{}'::jsonb,
  status              TEXT NOT NULL DEFAULT 'pending'
                      CHECK (status IN ('pending','sent','failed','skipped')),
  attempts            INTEGER NOT NULL DEFAULT 0,
  next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  provider_message_id TEXT,
  last_error          TEXT,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_pending ON notification_outbox (status, next_attempt_at)
  WHERE status = 'pending';

-- ── Audit log (append-only enforced in app + optional trigger) ─────────
CREATE TABLE audit_log (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  at            TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_label   TEXT,
  actor_id      BIGINT,
  role          TEXT,
  action        TEXT NOT NULL,
  details_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
  ip            TEXT,
  request_id    TEXT
);

CREATE INDEX idx_audit_at ON audit_log (at DESC);
CREATE INDEX idx_audit_action ON audit_log (action);

-- Block UPDATE/DELETE on audit_log at DB level
CREATE OR REPLACE FUNCTION audit_log_immutable()
RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_no_update
  BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();

-- ── Cash handovers ─────────────────────────────────────────────────────
CREATE TABLE cash_handovers (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  staff_id        BIGINT NOT NULL REFERENCES staff(id),
  business_date   DATE NOT NULL,
  expected_cash   INTEGER NOT NULL DEFAULT 0,
  handed_over     INTEGER,
  received_by     TEXT,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (staff_id, business_date)
);

-- ── Helpers ────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION booking_id_from_no(no BIGINT)
RETURNS TEXT AS $$
  SELECT 'JB-' || lpad(no::text, GREATEST(4, length(no::text)), '0');
$$ LANGUAGE SQL IMMUTABLE;

-- Default settings seed (English; hi/gu can be added later)
INSERT INTO settings (key, language, value) VALUES
  ('title', 'en', 'Diwali Sweets Booking'),
  ('subtitle', 'en', 'Community Trust — Pune'),
  ('notice', 'en', 'Book your Diwali sweets online. Pickup only. Limited window.'),
  ('terms', 'en',
    E'Pickup dates and time will be confirmed separately.\nParcels delivered by courier are not our responsibility after the prescribed dates.\nNo cancellations.\nNo refunds except automatic refund of failed technical payments.\nPlease collect sweets on the scheduled pickup dates only.'),
  ('thank_you', 'en', 'Thank you for your continued support'),
  ('booking_window_open', 'en', '2026-10-02T00:00:00+05:30'),
  ('booking_window_close', 'en', '2026-10-18T23:59:59+05:30'),
  ('booking_enabled', 'en', 'true'),
  ('allowed_pins', 'en', '411001-411062'),
  ('max_packets_per_item', 'en', '20'),
  ('max_packets_total', 'en', '50'),
  ('otp_provider', 'en', 'email'),
  ('privacy_notice', 'en',
    'We collect your name, mobile, email (optional), address and pin code only to process this booking and issue your receipt. Contact the trust to request deletion after the festival.');
