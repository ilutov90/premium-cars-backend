--liquibase formatted sql

--changeset premiumcars:025-admin-list-indexes
-- The admin car list with tens of thousands of cars (automatic import): its orders and its source filter.
CREATE INDEX idx_car_updated ON car (updated_at DESC, id DESC);
CREATE INDEX idx_car_created ON car (created_at DESC, id DESC);
CREATE INDEX idx_car_source_status ON car (source, status);
