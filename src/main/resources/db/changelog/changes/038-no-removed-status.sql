--liquibase formatted sql

--changeset premiumcars:038-no-removed-status
-- The status REMOVED is gone: a car whose listing disappears is deleted at once (and noted in auto_import_removed).
-- Cars an earlier version left hidden as REMOVED (normally none: every run deleted them) become ARCHIVED, which
-- hides them from the site the same way. car.removed_at stays, unused, so an older image can still be rolled back to.
UPDATE car SET status = 'ARCHIVED', updated_at = now() WHERE status = 'REMOVED';
