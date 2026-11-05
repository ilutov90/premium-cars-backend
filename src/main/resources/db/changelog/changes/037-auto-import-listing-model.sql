--liquibase formatted sql

--changeset premiumcars:037-auto-import-listing-model
-- The source's own model of a listing (its menu entry, e.g. autowini's "5 Series"), as the search found it: the
-- import names the car's model after it instead of the listing page's wording ("All New 5 Series").
ALTER TABLE auto_import_listing ADD COLUMN model VARCHAR(100);
