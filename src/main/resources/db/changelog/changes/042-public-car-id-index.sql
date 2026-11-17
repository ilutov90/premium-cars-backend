--liquibase formatted sql

--changeset premiumcars:042-public-car-id-index
-- The sitemap pages through the public cars by id (SitemapRepository): the partial index holds just those cars in id
-- order with the columns the page reads, so a page is an index-only scan and the count needs no table read.
CREATE INDEX idx_car_public_id ON car (id) INCLUDE (slug, updated_at) WHERE status IN ('PUBLISHED', 'RESERVED');
