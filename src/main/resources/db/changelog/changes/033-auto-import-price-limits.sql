--liquibase formatted sql

--changeset premiumcars:033-auto-import-price-limits
-- The highest FOB price (EUR) the automatic import takes (both sources); null = no limit. Listings above it are kept
-- as PRICE_SKIPPED and queued again when the limit (or their price) allows it.
ALTER TABLE auto_import_state ADD COLUMN max_price_eur INTEGER DEFAULT 100000;

-- Listings whose page shows no FOB price (guazi auctions): NO_PRICE, counted per run; after 3 runs in a row they are
-- not read again while their price in the search stays the same.
ALTER TABLE auto_import_listing ADD COLUMN no_price_count INTEGER NOT NULL DEFAULT 0;
UPDATE auto_import_listing SET state = 'NO_PRICE', no_price_count = 1, attempts = 0
    WHERE state = 'FAILED'
      AND (last_error LIKE 'No price could be read from the listing%' OR last_error LIKE 'No FOB price%');
