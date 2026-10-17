--liquibase formatted sql

--changeset premiumcars:006-social-posts
-- Posts published from the admin panel, so the admin sees what was posted where (and avoids double posts).
CREATE TABLE social_post (
    id          BIGSERIAL PRIMARY KEY,
    car_id      BIGINT       NOT NULL REFERENCES car (id) ON DELETE CASCADE,
    network     VARCHAR(20)  NOT NULL,
    external_id VARCHAR(100) NOT NULL,
    permalink   VARCHAR(500),
    photos      INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_social_post_car ON social_post (car_id);
