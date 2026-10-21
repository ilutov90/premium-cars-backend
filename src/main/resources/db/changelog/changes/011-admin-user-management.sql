--liquibase formatted sql

--changeset premiumcars:011-admin-user-management
-- Admins create other admins in the panel with a one-time password: the new admin must set an own password first.
ALTER TABLE admin_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;
