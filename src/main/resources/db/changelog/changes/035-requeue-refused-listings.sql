--liquibase formatted sql

--changeset premiumcars:035-requeue-refused-listings
-- Listings that failed because the site refused the server (HTTP 403/429, its bot protection) were not at fault:
-- queued again. From now on such a refusal pauses the source's queue instead (AutoImportWorker).
UPDATE auto_import_listing SET state = 'QUEUED', attempts = 0
    WHERE state = 'FAILED' AND (last_error LIKE '%HTTP 403%' OR last_error LIKE '%HTTP 429%');
