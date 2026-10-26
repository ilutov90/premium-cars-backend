--liquibase formatted sql

--changeset premiumcars:020-price-formula-duty
-- New price formula (October 2026), on the FOB price the site shows:
--   duty and VAT = duty_vat_pct % of (FOB + sea freight)
--   final price  = FOB + sea freight + land transport + commission + duty and VAT + profit
-- It replaces base costs + proportional step costs. Prices of existing cars stay as they were (with their old
-- breakdown) until an admin recalculates them.
ALTER TABLE pricing_profile
    ADD COLUMN sea_freight_eur    NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (sea_freight_eur >= 0),
    ADD COLUMN land_transport_eur NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (land_transport_eur >= 0),
    ADD COLUMN commission_eur     NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (commission_eur >= 0),
    ADD COLUMN duty_vat_pct       NUMERIC(5, 2)  NOT NULL DEFAULT 0 CHECK (duty_vat_pct BETWEEN 0 AND 100);

UPDATE pricing_profile
SET sea_freight_eur = 2000, land_transport_eur = 1600, commission_eur = 500, profit_eur = 2000, duty_vat_pct = 20,
    updated_at = now()
WHERE id = 'AUTOHOME';

UPDATE pricing_profile
SET sea_freight_eur = 1200, land_transport_eur = 1600, commission_eur = 500, profit_eur = 2000, duty_vat_pct = 17,
    updated_at = now()
WHERE id = 'AUTOWINI';

ALTER TABLE pricing_profile
    DROP COLUMN base_costs_eur,
    DROP COLUMN threshold_eur,
    DROP COLUMN step_eur,
    DROP COLUMN step_cost_eur;
