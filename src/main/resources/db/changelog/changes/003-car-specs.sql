--liquibase formatted sql

--changeset premiumcars:003-car-specs
ALTER TABLE car
    ADD COLUMN first_registration VARCHAR(20),
    ADD COLUMN drivetrain         VARCHAR(10),
    ADD COLUMN engine_cc          INTEGER,
    ADD COLUMN battery_kwh        NUMERIC(6, 1),
    ADD COLUMN electric_range_km  INTEGER,
    ADD COLUMN range_standard     VARCHAR(10),
    ADD COLUMN motor_power_kw     INTEGER,
    ADD COLUMN seats              INTEGER,
    ADD COLUMN doors              INTEGER,
    ADD COLUMN curb_weight_kg     INTEGER,
    ADD COLUMN dimensions         VARCHAR(50),
    ADD COLUMN source_location    VARCHAR(100);

--changeset premiumcars:003-demo-ev context:demo
UPDATE car
SET battery_kwh = 82.5, electric_range_km = 650, range_standard = 'CLTC', motor_power_kw = 390, power_hp = 530,
    drivetrain = 'AWD', seats = 5, doors = 4, specs = '{}'::jsonb
WHERE slug = 'demo-byd-seal-2023';
