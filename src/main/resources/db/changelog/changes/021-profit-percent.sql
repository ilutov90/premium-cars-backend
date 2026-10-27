--liquibase formatted sql

--changeset premiumcars:021-profit-percent
-- The profit is a percent of the FOB price with a minimum (October 2026): 10 % of FOB, at least 1000 EUR, for every
-- site. It replaces the fixed profit. Prices of existing cars stay as they were until an admin recalculates them.
ALTER TABLE pricing_profile
    ADD COLUMN profit_pct     NUMERIC(5, 2)  NOT NULL DEFAULT 10   CHECK (profit_pct BETWEEN 0 AND 100),
    ADD COLUMN profit_min_eur NUMERIC(12, 2) NOT NULL DEFAULT 1000 CHECK (profit_min_eur >= 0);

UPDATE pricing_profile SET profit_pct = 10, profit_min_eur = 1000, updated_at = now();

ALTER TABLE pricing_profile DROP COLUMN profit_eur;
