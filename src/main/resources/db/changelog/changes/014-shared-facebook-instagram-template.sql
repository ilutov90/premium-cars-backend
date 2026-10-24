--liquibase formatted sql

--changeset premiumcars:014-shared-facebook-instagram-template
-- Facebook and Instagram share one post template, stored as FACEBOOK; Viber keeps its own. The two were the same
-- text, so the Facebook one stays (or the Instagram one becomes it when only that one was saved).
UPDATE post_template SET network = 'FACEBOOK'
WHERE network = 'INSTAGRAM' AND NOT EXISTS (SELECT 1 FROM post_template WHERE network = 'FACEBOOK');
DELETE FROM post_template WHERE network = 'INSTAGRAM';
