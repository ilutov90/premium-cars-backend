--liquibase formatted sql

--changeset premiumcars:027-step-and-markup
-- The price formula of October 31, 2026 (every value editable in the admin panel "Цени"):
--   step   = step base + step % of the FOB above "step from" (autowini: 1700 + 10 % of FOB over 10 000; China: none)
--   markup = a fixed amount: markup low up to the threshold FOB, markup high above it (1000 / 1500, 25 000)
--   final  = FOB + sea freight + land transport + commission + duty and VAT % of (FOB + sea) + step + markup
-- autowini's step replaces its duty and VAT (0 %). Prices already calculated stay until an admin recalculates them.

ALTER TABLE pricing_profile
    ADD COLUMN step_base_eur        NUMERIC(12, 2) NOT NULL DEFAULT 0     CHECK (step_base_eur >= 0),
    ADD COLUMN step_from_eur        NUMERIC(12, 2) NOT NULL DEFAULT 0     CHECK (step_from_eur >= 0),
    ADD COLUMN step_pct             NUMERIC(5, 2)  NOT NULL DEFAULT 0     CHECK (step_pct BETWEEN 0 AND 100),
    ADD COLUMN markup_low_eur       NUMERIC(12, 2) NOT NULL DEFAULT 1000  CHECK (markup_low_eur >= 0),
    ADD COLUMN markup_high_eur      NUMERIC(12, 2) NOT NULL DEFAULT 1500  CHECK (markup_high_eur >= 0),
    ADD COLUMN markup_threshold_eur NUMERIC(12, 2) NOT NULL DEFAULT 25000 CHECK (markup_threshold_eur >= 0);

UPDATE pricing_profile
SET sea_freight_eur = 1200, land_transport_eur = 1600, commission_eur = 500, duty_vat_pct = 0,
    step_base_eur = 1700, step_from_eur = 10000, step_pct = 10, updated_at = now()
WHERE id = 'AUTOWINI';

UPDATE pricing_profile SET markup_low_eur = 1000, markup_high_eur = 1500, markup_threshold_eur = 25000,
    updated_at = now();

ALTER TABLE pricing_profile DROP COLUMN profit_pct, DROP COLUMN profit_min_eur;
