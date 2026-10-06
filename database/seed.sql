-- Staging sample catalogue (Admin edits before go-live)
INSERT INTO items (name_en, name_hi, name_gu, pack_size, price_paise, weight_kg, active, sort_order) VALUES
  ('Kaju Katli', 'काजू कतली', 'કાજૂ કતલી', '500 g box', 55000, 0.500, TRUE, 1),
  ('Motichoor Ladoo', 'मोतीचूर लड्डू', 'મોતીચૂર લાડુ', '1 kg', 48000, 1.000, TRUE, 2),
  ('Gulab Jamun', 'गुलाब जामुन', 'ગુલાબ જામુન', '1 kg box', 42000, 1.000, TRUE, 3),
  ('Soan Papdi', 'सोन पापड़ी', 'સોન પાપડી', '500 g box', 25000, 0.500, TRUE, 4),
  ('Dry Fruit Assortment', 'ड्राई फ्रूट असॉर्टमेंट', 'ડ્રાય ફ્રૂટ અસૉર્ટમેન્ટ', '750 g box', 85000, 0.750, TRUE, 5);

-- Placeholder staff — replace via Admin UI before go-live
INSERT INTO staff (email, name, role, active) VALUES
  ('admin1@example.com', 'Admin One', 'ADMIN', TRUE),
  ('admin2@example.com', 'Admin Two', 'ADMIN', TRUE),
  ('counter1@example.com', 'Counter One', 'COUNTER', TRUE),
  ('counter2@example.com', 'Counter Two', 'COUNTER', TRUE);

-- Real staff (Google sign-in allowlist). Only emails with an active row here
-- can log in, after the Google OIDC exchange succeeds. This file runs before Flyway,
-- so only V1 roles are allowed here: the V2 migration then promotes this account to
-- SUPER_ADMIN and adds tudani2009@gmail.com as ADMIN.
INSERT INTO staff (email, name, role, active) VALUES
  ('shahamitatjb@gmail.com', 'Amit Shah', 'ADMIN', TRUE)
ON CONFLICT (email) DO UPDATE SET active = TRUE, role = EXCLUDED.role, name = EXCLUDED.name;

-- Hindi / Gujarati texts can be inserted later with fallback to English in app
-- INSERT INTO settings (key, language, value) VALUES ('title','hi','...'), ('title','gu','...');
