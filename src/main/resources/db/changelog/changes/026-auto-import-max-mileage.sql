--liquibase formatted sql

--changeset premiumcars:026-auto-import-max-mileage
-- The automatic import skips listings over a mileage (October 2026): one limit for both sources, edited in the
-- admin tab, null = no limit. Listings over it are kept as SKIPPED with their mileage, so a higher limit queues them.

ALTER TABLE auto_import_state ADD COLUMN max_mileage_km INTEGER DEFAULT 200000
    CHECK (max_mileage_km IS NULL OR max_mileage_km > 0);
ALTER TABLE auto_import_listing ADD COLUMN mileage_km INTEGER;
