-- Super admin and treasurer roles, plus per-booking payment reconciliation.

ALTER TABLE staff DROP CONSTRAINT staff_role_check;
ALTER TABLE staff ADD CONSTRAINT staff_role_check
  CHECK (role IN ('SUPER_ADMIN','ADMIN','TREASURER','COUNTER'));

-- Preconfigured accounts, so a fresh deployment always has someone who can sign in.
INSERT INTO staff (email, name, role, active) VALUES ('shahamitatjb@gmail.com', 'Amit Shah', 'SUPER_ADMIN', TRUE)
ON CONFLICT (email) DO UPDATE SET role = 'SUPER_ADMIN';
INSERT INTO staff (email, name, role, active) VALUES ('tudani2009@gmail.com', NULL, 'ADMIN', TRUE)
ON CONFLICT (email) DO NOTHING;

ALTER TABLE bookings ADD COLUMN reconciled_at  TIMESTAMPTZ;
ALTER TABLE bookings ADD COLUMN reconciled_by  BIGINT REFERENCES staff(id);
ALTER TABLE bookings ADD COLUMN reconcile_note TEXT;
