--liquibase formatted sql

--changeset premiumcars:024-inspection
-- The source's inspection report (guazi): grade, checks, areas and the defects found with photo and video,
-- shown on the car page. Read-only like spec_sheet; filled by the import.
ALTER TABLE car ADD COLUMN inspection JSONB;
