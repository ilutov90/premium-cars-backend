--liquibase formatted sql

--changeset premiumcars:009-post-templates
-- Post text templates per network, edited in the admin panel ("Шаблони"). No row = the frontend's default.
-- Placeholders such as {{price}} are filled in by the admin panel when a car is published.
CREATE TABLE post_template (
    network    VARCHAR(20) PRIMARY KEY,
    body       TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
