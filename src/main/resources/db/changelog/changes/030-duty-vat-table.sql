--liquibase formatted sql

--changeset premiumcars:030-duty-vat-table
-- autowini's duty and VAT from a table by FOB (October 31, 2026), editable in the admin panel "Цени": the first row
-- whose "FOB up to" is at least the car's FOB (15 700 -> the 16 000 row); the row without "up to" is for every FOB
-- above the others. A formula with rows uses them instead of its duty and VAT percent; the step of 027 goes.
-- Prices already calculated stay until an admin recalculates them.

CREATE TABLE pricing_duty_bracket (
    profile_id   VARCHAR(20)    NOT NULL REFERENCES pricing_profile (id),
    fob_up_to    NUMERIC(12, 2) CHECK (fob_up_to >= 0),   -- null = above every other row
    duty_vat_eur NUMERIC(12, 2) NOT NULL CHECK (duty_vat_eur >= 0)
);
CREATE UNIQUE INDEX uq_pricing_duty_bracket ON pricing_duty_bracket (profile_id, COALESCE(fob_up_to, -1));

INSERT INTO pricing_duty_bracket (profile_id, fob_up_to, duty_vat_eur) VALUES
    ('AUTOWINI', 10000, 2300), ('AUTOWINI', 11000, 2360), ('AUTOWINI', 12000, 2420), ('AUTOWINI', 13000, 2480),
    ('AUTOWINI', 14000, 2540), ('AUTOWINI', 15000, 2600), ('AUTOWINI', 16000, 2680), ('AUTOWINI', 17000, 2760),
    ('AUTOWINI', 18000, 2840), ('AUTOWINI', 19000, 2920), ('AUTOWINI', 20000, 3000), ('AUTOWINI', 21000, 3080),
    ('AUTOWINI', 22000, 3160), ('AUTOWINI', 23000, 3240), ('AUTOWINI', 24000, 3320), ('AUTOWINI', 25000, 3400),
    ('AUTOWINI', 26000, 3480), ('AUTOWINI', 27000, 3560), ('AUTOWINI', 28000, 3640), ('AUTOWINI', 29000, 3720),
    ('AUTOWINI', 30000, 3800), ('AUTOWINI', NULL, 4000);

UPDATE pricing_profile SET duty_vat_pct = 0, updated_at = now() WHERE id = 'AUTOWINI';

ALTER TABLE pricing_profile DROP COLUMN step_base_eur, DROP COLUMN step_from_eur, DROP COLUMN step_pct;
