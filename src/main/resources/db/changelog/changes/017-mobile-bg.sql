--liquibase formatted sql

--changeset premiumcars:017-mobile-bg
-- mobile.bg's lists for the "Добави обява" form (brands/models, select options, extras), as an admin's browser
-- extension last read them: one row.
CREATE TABLE mobile_bg_catalog (
    id         SMALLINT PRIMARY KEY CHECK (id = 1),
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(50)
);

-- Where a car was posted on mobile.bg (reported by the extension when the admin published it there).
CREATE TABLE mobile_bg_listing (
    car_id       BIGINT PRIMARY KEY REFERENCES car (id) ON DELETE CASCADE,
    listing_id   VARCHAR(40),
    url          VARCHAR(600),
    published_at TIMESTAMPTZ NOT NULL,
    published_by VARCHAR(50)
);
