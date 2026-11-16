package com.premiumcars.catalog.web.dto;

import java.math.BigDecimal;
import java.util.List;

import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.domain.Transmission;

/** Query parameters of GET /api/public/cars. All optional. */
public record CarFilter(
        String q,
        String make,
        String model,
        FuelType fuelType,
        Transmission transmission,
        Origin origin,
        Integer yearFrom,
        Integer yearTo,
        BigDecimal priceFrom,
        BigDecimal priceTo,
        Integer mileageTo,
        List<String> makes,
        List<String> models
) {

    /** The filter without the repeated make / model lists. */
    public CarFilter(String q, String make, String model, FuelType fuelType, Transmission transmission, Origin origin,
                     Integer yearFrom, Integer yearTo, BigDecimal priceFrom, BigDecimal priceTo, Integer mileageTo) {
        this(q, make, model, fuelType, transmission, origin, yearFrom, yearTo, priceFrom, priceTo, mileageTo, null,
                null);
    }

    /** The same filter with these exact make and model names (any of them), taking the place of {@code make} / {@code model}. */
    public CarFilter withNames(List<String> makeNames, List<String> modelNames) {
        return new CarFilter(q, makeNames == null ? make : null, modelNames == null ? model : null, fuelType,
                transmission, origin, yearFrom, yearTo, priceFrom, priceTo, mileageTo, makeNames, modelNames);
    }
}
