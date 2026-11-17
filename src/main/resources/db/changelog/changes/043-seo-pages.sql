--liquibase formatted sql

--changeset premiumcars:043-seo-pages
-- The admins' texts for the SEO landing pages per make and per make + model (null = the frontend's automatic text).
-- model_slug '' is the make's own page.
CREATE TABLE seo_page (
    make_slug   VARCHAR(120) NOT NULL,
    model_slug  VARCHAR(160) NOT NULL DEFAULT '',
    title       VARCHAR(160),
    description VARCHAR(320),
    body        TEXT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by  VARCHAR(100),
    PRIMARY KEY (make_slug, model_slug)
);
