--liquibase formatted sql

--changeset premiumcars:045-autotrader-ca
-- autotrader.ca (Canada, prices in CAD, converted to EUR by the exchange rate): its own price formula, placeholder
-- numbers until the owner sets them in the admin panel "Цени" (no duty and VAT table: the percent applies), and its
-- automatic import row, switched off until the formula is set.
INSERT INTO pricing_profile (id, sea_freight_eur, land_transport_eur, commission_eur, duty_vat_pct,
                             markup_low_eur, markup_high_eur, markup_threshold_eur)
VALUES ('AUTOTRADER_CA', 1500, 1600, 500, 20, 1000, 1500, 25000);

INSERT INTO auto_import_source (source, enabled, filters)
VALUES ('AUTOTRADER_CA', false, '{}');
