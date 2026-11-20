--liquibase formatted sql

--changeset premiumcars:047-order-car-photos
-- The storage paths of the sold car's stored photos (gallery order), kept by the order so the photos survive the car's
-- deletion by hand. A JSON array of strings; the photo files are deleted with the order, once no car uses them.
ALTER TABLE customer_order ADD COLUMN car_photos JSONB NOT NULL DEFAULT '[]'::jsonb;
