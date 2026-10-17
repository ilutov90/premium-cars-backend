--liquibase formatted sql

--changeset premiumcars:005-proportional-steps
-- Costs above the threshold are always proportional to the car price (5000 € above 20 000 € = half a step),
-- so the step mode is no longer a setting.
ALTER TABLE pricing_profile DROP COLUMN step_mode;
