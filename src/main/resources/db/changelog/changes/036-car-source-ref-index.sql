--liquibase formatted sql

--changeset premiumcars:036-car-source-ref-index
-- A car is looked up by its listing's own id (CarRepository.findBySourceRef, case ignored) for every listing a run
-- sees, and the auto-import export joins all listings to their cars by it: without an index each lookup read the
-- whole car table.
CREATE INDEX idx_car_source_ref ON car (source, lower(source_ref));
