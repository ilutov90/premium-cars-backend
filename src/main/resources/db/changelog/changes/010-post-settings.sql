--liquibase formatted sql

--changeset premiumcars:010-post-settings
-- Business details shared by all post templates ({{phone}}, {{viber_link}}, {{office}}), edited in the admin
-- panel ("Шаблони"). No row = the admin panel's default.
CREATE TABLE post_setting (
    name       VARCHAR(30) PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
