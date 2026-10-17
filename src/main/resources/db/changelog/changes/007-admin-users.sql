--liquibase formatted sql

--changeset premiumcars:007-admin-users
-- Admin panel users. Passwords are stored as bcrypt hashes ({bcrypt}... from Spring's DelegatingPasswordEncoder).
-- The first user is created at startup from ADMIN_INITIAL_PASSWORD (Vault) when the table is empty.
CREATE TABLE admin_user (
    id                  BIGSERIAL PRIMARY KEY,
    username            VARCHAR(50)  NOT NULL UNIQUE,
    password_hash       VARCHAR(200) NOT NULL,
    failed_attempts     INT          NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
