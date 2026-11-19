--liquibase formatted sql

--changeset premiumcars:044-scheduled-post-link-only
-- A Viber post may be link only: just the text (the car's link) is sent, no photos.
ALTER TABLE scheduled_post ADD COLUMN link_only boolean NOT NULL DEFAULT false;
