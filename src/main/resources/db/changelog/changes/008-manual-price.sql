--liquibase formatted sql

--changeset premiumcars:008-manual-price
-- Per-car corrections of the calculated price, set in the admin car editor (NULL = use the calculation):
-- profit_eur replaces the profile's profit for this car; fixed_total_eur replaces the whole final price.
ALTER TABLE car
    ADD COLUMN profit_eur      NUMERIC(14, 2),
    ADD COLUMN fixed_total_eur NUMERIC(14, 2);
