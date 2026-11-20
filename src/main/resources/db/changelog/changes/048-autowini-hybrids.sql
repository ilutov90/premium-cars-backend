--liquibase formatted sql

--changeset premiumcars:048-autowini-hybrids
-- autowini names hybrids "Gasoline+Electric" (or "Diesel+Electric", "LPG+Electric"), which were read as electric.
-- A car saved as electric that has an engine displacement is such a hybrid: pure electric cars have none.
UPDATE car SET fuel_type = 'HYBRID', updated_at = now()
WHERE source = 'AUTOWINI' AND fuel_type = 'ELECTRIC' AND engine_cc IS NOT NULL AND engine_cc > 0
  -- the Nissan Leaf is electric only (a few were saved with a displacement by mistake)
  AND NOT (make ILIKE 'nissan' AND model ILIKE 'leaf%');
