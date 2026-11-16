--liquibase formatted sql

--changeset premiumcars:039-auto-import-removed-facts
-- What a deleted car was (its listing was gone at the source), kept for statistics of what sells: the make and model
-- as the source site's own menus name them (auto_import_catalog / auto_import_brand; make null = not recognised; the
-- model is the car's own text when the menu has no match), the year, fuel type and mileage of the car, its FOB price
-- in EUR (the kept price breakdown) and final customer price, and when the listing (or the car) first appeared with
-- us. Rows noted before 039 are named once after startup from their title and slug (RemovedCarNaming).
ALTER TABLE auto_import_removed
    ADD COLUMN make          VARCHAR(100),
    ADD COLUMN model         VARCHAR(150),
    ADD COLUMN year          INTEGER,
    ADD COLUMN fuel_type     VARCHAR(30),
    ADD COLUMN mileage_km    INTEGER,
    ADD COLUMN fob_price_eur NUMERIC(12, 2),
    ADD COLUMN price_eur     NUMERIC(12, 2),
    ADD COLUMN first_seen    TIMESTAMPTZ;
CREATE INDEX idx_auto_import_removed_removed_at ON auto_import_removed (removed_at);
