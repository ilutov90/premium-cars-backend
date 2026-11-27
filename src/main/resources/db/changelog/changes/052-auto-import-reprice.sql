--liquibase formatted sql

--changeset premiumcars:052-auto-import-reprice
-- A linked car whose price in the source's search changed (more than the run's typical move): its listing is read
-- again and its price recalculated after the run; cleared once that is done.
ALTER TABLE auto_import_listing ADD COLUMN reprice_since TIMESTAMPTZ;
CREATE INDEX idx_auto_import_listing_reprice ON auto_import_listing (source) WHERE reprice_since IS NOT NULL;
