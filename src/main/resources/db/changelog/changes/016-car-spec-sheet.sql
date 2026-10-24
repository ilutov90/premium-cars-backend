--liquibase formatted sql

--changeset premiumcars:016-car-spec-sheet
-- The source site's full specification sheet as imported ([{name, items: [{name, value}]}]), shown on the car page.
ALTER TABLE car ADD COLUMN spec_sheet JSONB NOT NULL DEFAULT '[]'::jsonb;
