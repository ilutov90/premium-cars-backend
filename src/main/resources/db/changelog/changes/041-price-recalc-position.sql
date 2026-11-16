--liquibase formatted sql

--changeset premiumcars:041-price-recalc-position
-- How far a price recalculation got, so a run cut by a restart continues where it stopped: the cars are taken by id,
-- last_car_id = the last car done (null = none yet), end_car_id = the largest id of the run's cars when it started
-- (0 = none; the run never goes past it). Runs started before 041 have neither: at a restart such a run continues after the last car it noted (its items), or is
-- abandoned when it noted none.
ALTER TABLE price_recalc_run
    ADD COLUMN last_car_id BIGINT,
    ADD COLUMN end_car_id  BIGINT;
