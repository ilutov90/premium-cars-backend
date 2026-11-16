--liquibase formatted sql

--changeset premiumcars:040-removed-stats-reset
-- The statistics of the cars removed from the sources ("Изчезнали обяви"): removed_stats_from = the moment an admin
-- reset them (only later removals count; null = all), and out_of_filters = the car was deleted only because it is no
-- longer inside the import's filters (its make, model, year, fuel type or grade was taken out), so its listing was
-- not looked for: not counted as sold.
ALTER TABLE auto_import_state ADD COLUMN removed_stats_from TIMESTAMPTZ;
ALTER TABLE auto_import_removed ADD COLUMN out_of_filters BOOLEAN NOT NULL DEFAULT false;
