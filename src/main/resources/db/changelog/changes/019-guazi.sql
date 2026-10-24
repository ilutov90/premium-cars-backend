--liquibase formatted sql

--changeset premiumcars:019-battery-health
-- Traction battery state of health (SOH, %), as en.guazi.com's "Battery Report" shows it; null = not known.
ALTER TABLE car ADD COLUMN battery_health_pct SMALLINT CHECK (battery_health_pct BETWEEN 0 AND 100);
