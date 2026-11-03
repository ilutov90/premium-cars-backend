--liquibase formatted sql

--changeset premiumcars:034-drop-first-registration
-- The sources' first registration was unreliable (autowini sometimes gave a note instead of a date) and only added
-- the month to the model year: the model year is used everywhere (mobile.bg and cars.bg take January).
ALTER TABLE car DROP COLUMN first_registration;
