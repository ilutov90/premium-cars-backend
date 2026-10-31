--liquibase formatted sql

--changeset premiumcars:032-auto-import-fuel-types
-- autowini's fuel types not to import for a whole make (when no model is chosen); a chosen model keeps its own in
-- auto_import_brand.models ("excludedFuels").
ALTER TABLE auto_import_brand ADD COLUMN excluded_fuels JSONB NOT NULL DEFAULT '[]';
