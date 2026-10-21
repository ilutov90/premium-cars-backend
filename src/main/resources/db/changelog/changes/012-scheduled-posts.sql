--liquibase formatted sql

--changeset premiumcars:012-scheduled-posts
-- Posts to Facebook / Instagram / Viber planned for a later time. The backend checks every 30 seconds and publishes
-- the due ones (ScheduledPostService); the text and the photos are fixed when the post is scheduled.
CREATE TABLE scheduled_post (
    id             BIGSERIAL PRIMARY KEY,
    car_id         BIGINT        NOT NULL REFERENCES car (id) ON DELETE CASCADE,
    network        VARCHAR(20)   NOT NULL,
    body           TEXT          NOT NULL,
    image_ids      JSONB         NOT NULL DEFAULT '[]',
    publish_at     TIMESTAMPTZ   NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'SCHEDULED',
    attempts       INT           NOT NULL DEFAULT 0,
    error          VARCHAR(1000),
    social_post_id BIGINT        REFERENCES social_post (id) ON DELETE SET NULL,
    created_by     VARCHAR(50),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_scheduled_post_due ON scheduled_post (publish_at) WHERE status = 'SCHEDULED';
CREATE INDEX idx_scheduled_post_car ON scheduled_post (car_id);
