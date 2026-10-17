--liquibase formatted sql

--changeset premiumcars:004-pricing-profiles
-- One price formula per source site, editable in the admin panel. All amounts in EUR:
--   final price = car price + base costs + step cost for every step above the threshold + profit
CREATE TABLE pricing_profile (
    id             VARCHAR(20) PRIMARY KEY,
    base_costs_eur NUMERIC(12, 2) NOT NULL CHECK (base_costs_eur >= 0),
    threshold_eur  NUMERIC(12, 2) NOT NULL CHECK (threshold_eur >= 0),
    step_eur       NUMERIC(12, 2) NOT NULL CHECK (step_eur > 0),
    step_cost_eur  NUMERIC(12, 2) NOT NULL CHECK (step_cost_eur >= 0),
    step_mode      VARCHAR(20)    NOT NULL,
    profit_eur     NUMERIC(12, 2) NOT NULL CHECK (profit_eur >= 0),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

INSERT INTO pricing_profile (id, base_costs_eur, threshold_eur, step_eur, step_cost_eur, step_mode, profit_eur)
VALUES ('AUTOHOME', 5860, 10000, 10000, 2880, 'STARTED', 2000),
       ('AUTOWINI', 5000, 10000, 10000, 600, 'STARTED', 2000);

-- Which formula prices the car. Imports get their site's profile; other cars get one in the editor.
ALTER TABLE car ADD COLUMN price_profile VARCHAR(20) REFERENCES pricing_profile (id);
UPDATE car SET price_profile = source WHERE source IN ('AUTOHOME', 'AUTOWINI');

-- The old landed-cost inputs (duty, VAT, shipping, margin) are replaced by the profiles.
-- total_price_eur is recalculated at startup.
ALTER TABLE car DROP COLUMN shipping_eur, DROP COLUMN margin_eur, DROP COLUMN extra_duty_rate;
