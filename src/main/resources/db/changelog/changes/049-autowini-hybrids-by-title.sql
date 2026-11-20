--liquibase formatted sql

--changeset premiumcars:049-autowini-hybrids-by-title
-- 048 missed the autowini hybrids whose page gives the engine as "0cc": autowini's "Gasoline+Electric" covers full,
-- mild (Mercedes, BMW, Audi 48 V) and plug-in hybrids, and they all still showed as electric. Only the cars whose
-- title names an electric car stay electric (EV6, Leaf, Ioniq 5/6, EQ…, e-tron, Taycan, BMW i/iX, Tesla, …).
-- 1. plug-in: "PHEV", "plug-in", BMW/Mercedes "320e", "530e", "E300e", "GLC 300 e", "C350e", Jeep "4xe"
UPDATE car SET fuel_type = 'PLUGIN_HYBRID', updated_at = now()
WHERE source = 'AUTOWINI' AND fuel_type = 'ELECTRIC'
  AND (title ~* '\mphev\M' OR title ~* 'plug[- ]?in' OR title ~* '\m[a-z]{0,3}[0-9]{2,3} ?e\M' OR title ~* '\m4xe\M');

-- 2. every other one that is not an electric car by its title
UPDATE car SET fuel_type = 'HYBRID', updated_at = now()
WHERE source = 'AUTOWINI' AND fuel_type = 'ELECTRIC'
  AND NOT (title ~* '\mev[0-9]*\M' OR title ~* 'leaf' OR title ~* 'ioniq ?[56]' OR title ~* '\meq[a-z]'
           OR title ~* 'electric' OR title ~* 'e-tron' OR title ~* 'taycan' OR title ~* '\mi[3-7x]\M'
           OR title ~* '\mix[0-9]?\M' OR title ~* '\mbolt\M' OR title ~* 'polestar' OR title ~* 'tesla'
           OR title ~* 'nexo');
