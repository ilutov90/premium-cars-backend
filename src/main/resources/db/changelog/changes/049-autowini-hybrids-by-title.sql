--liquibase formatted sql

--changeset premiumcars:049-autowini-hybrids-by-title
-- 048 missed the autowini hybrids whose page gives the engine as "0cc": they still showed as electric. Their title
-- names them ("Tucson Hybrid 1.6 Turbo", "1.6T HEV"); a pure electric car's title never says hybrid or HEV.
UPDATE car SET fuel_type = 'PLUGIN_HYBRID', updated_at = now()
WHERE source = 'AUTOWINI' AND fuel_type = 'ELECTRIC'
  AND (title ~* '\mphev\M' OR title ~* 'plug[- ]?in');

UPDATE car SET fuel_type = 'HYBRID', updated_at = now()
WHERE source = 'AUTOWINI' AND fuel_type = 'ELECTRIC'
  AND (title ~* 'hybrid' OR title ~* '\mhev\M');
