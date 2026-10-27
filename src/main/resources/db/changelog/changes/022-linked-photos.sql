--liquibase formatted sql

--changeset premiumcars:022-linked-photos
-- Photos of sites whose CDN serves them to any browser (guazi, autohome) are no longer downloaded: the site shows
-- them from the source's URL (source_url) and storage_path is null. Sites that refuse other sites' visitors
-- (autowini) keep a stored copy.
ALTER TABLE car_image ALTER COLUMN storage_path DROP NOT NULL;
ALTER TABLE car_image ADD CONSTRAINT car_image_stored_or_linked
    CHECK (storage_path IS NOT NULL OR source_url IS NOT NULL);
