--liquibase formatted sql

--changeset premiumcars:013-frozen-prices
-- A car's price is calculated once (at import, or when its own price fields change, or on an explicit
-- "recalculate") and then kept: posts on Facebook / Instagram / Viber show that price. The breakdown it was
-- built from (exchange rate, costs, profit) is kept with it, so the admin panel shows how it was calculated.
ALTER TABLE car ADD COLUMN price_breakdown JSONB;
