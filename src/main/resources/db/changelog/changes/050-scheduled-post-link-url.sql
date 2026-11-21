--liquibase formatted sql

--changeset premiumcars:050-scheduled-post-link-url
-- A Viber post without photos may be the car's link (shown with the page's preview) with a short text under it:
-- the text is the body, the link is kept here. Null = the body alone (the link-only post of 044).
ALTER TABLE scheduled_post ADD COLUMN link_url VARCHAR(2000);
