--liquibase formatted sql

-- Three sample cars (no photos) so the site is not empty on first start.
-- Runs only with the "demo" Liquibase context. Prices follow the default pricing settings;
-- after changing them call POST /api/admin/cars/recalculate-prices.

--changeset premiumcars:002-demo-cars context:demo
INSERT INTO car (slug, status, source, title, make, model, model_year, mileage_km, fuel_type, transmission,
                 body_type, engine, color, description, specs, source_price, source_currency,
                 total_price_eur, created_at, updated_at, published_at)
VALUES
    ('demo-hyundai-tucson-2021', 'PUBLISHED', 'AUTOWINI', 'Hyundai Tucson 1.6 T-GDi 4WD', 'Hyundai', 'Tucson',
     2021, 48000, 'PETROL', 'AUTOMATIC', 'SUV', '1.6 T-GDi', 'White',
     'Demo listing. Replace it with a real import from the admin panel.',
     '{"Drive": "4WD", "Seats": "5"}', 17500, 'USD',
     ROUND((17500 * 0.86 + 2500) * 1.10 * 1.20 + 600 + 1500), now(), now(), now()),
    ('demo-byd-seal-2023', 'PUBLISHED', 'AUTOHOME', 'BYD Seal 650 Premium', 'BYD', 'Seal',
     2023, 21000, 'ELECTRIC', 'AUTOMATIC', 'Sedan', 'Dual motor', 'Blue',
     'Demo listing. Chinese electric cars may carry extra EU duty — set it per car in the admin panel.',
     '{"Battery": "82.5 kWh", "Range (CLTC)": "650 km"}', 19800, 'USD',
     ROUND((19800 * 0.86 + 2500) * 1.10 * 1.20 + 600 + 1500), now(), now(), now() - interval '1 day'),
    ('demo-kia-sorento-2020', 'RESERVED', 'AUTOWINI', 'Kia Sorento 2.2 CRDi', 'Kia', 'Sorento',
     2020, 76000, 'DIESEL', 'AUTOMATIC', 'SUV', '2.2 CRDi', 'Grey',
     'Demo listing.', '{"Seats": "7"}', 16200, 'USD',
     ROUND((16200 * 0.86 + 2500) * 1.10 * 1.20 + 600 + 1500), now(), now(), now() - interval '2 days');
