--liquibase formatted sql

--changeset premiumcars:015-car-features
-- euro_standard: emission class 1-6 (mobile.bg "Евростандарт"); features: CarFeature names (mobile.bg extras)
ALTER TABLE car
    ADD COLUMN euro_standard INTEGER,
    ADD COLUMN features      JSONB NOT NULL DEFAULT '[]'::jsonb;
