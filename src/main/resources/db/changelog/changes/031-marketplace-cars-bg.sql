--liquibase formatted sql

--changeset premiumcars:031-marketplace-cars-bg
-- An admin's choice of a listing site's make + model for one of our makes + models (the page "Съпоставяне на
-- модели"), or that the site has none (unavailable). Keyed by our names' keys (lower case, letters and digits only,
-- the model without the sources' powertrain words: ModelNames).
CREATE TABLE marketplace_model_map (
    site         VARCHAR(20)  NOT NULL,
    make_key     VARCHAR(100) NOT NULL,
    model_key    VARCHAR(100) NOT NULL,
    make         VARCHAR(100) NOT NULL,
    model        VARCHAR(100) NOT NULL,
    target_brand VARCHAR(100),
    target_model VARCHAR(100),
    unavailable  BOOLEAN      NOT NULL DEFAULT FALSE,
    set_by       VARCHAR(50),
    set_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (site, make_key, model_key),
    CHECK (unavailable OR (target_brand IS NOT NULL AND target_model IS NOT NULL))
);

-- cars.bg's makes and models with their ids, as an admin's browser extension last read them: one row.
CREATE TABLE cars_bg_catalog (
    id         SMALLINT PRIMARY KEY CHECK (id = 1),
    data       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(50)
);

-- Where a car was posted on cars.bg (reported by the extension when the admin published it there).
CREATE TABLE cars_bg_listing (
    car_id       BIGINT PRIMARY KEY REFERENCES car (id) ON DELETE CASCADE,
    offer_id     VARCHAR(40),
    url          VARCHAR(600),
    published_at TIMESTAMPTZ NOT NULL,
    published_by VARCHAR(50)
);

-- One name per make (MakeNames, applied at import from now on): the sources' sub-brands become the make.
UPDATE car SET make = 'Geely', model = CASE
        WHEN model IS NULL OR btrim(model) = '' OR lower(btrim(model)) LIKE 'galaxy%' THEN model
        ELSE left('Galaxy ' || btrim(model), 100) END
    WHERE lower(btrim(make)) = 'geely galaxy';
UPDATE car SET make = 'Geely' WHERE lower(btrim(make)) = 'geely auto';
UPDATE car SET make = 'Dongfeng' WHERE lower(btrim(make)) IN ('dongfeng aeolus', 'dongfeng fengshen');
UPDATE car SET make = 'Xiaomi' WHERE lower(btrim(make)) = 'xiaomi auto';
