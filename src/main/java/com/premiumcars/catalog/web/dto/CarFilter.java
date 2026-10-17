package com.premiumcars.catalog.web.dto;

import java.math.BigDecimal;

import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.domain.Transmission;

/** Query parameters of GET /api/public/cars. All optional. */
public record CarFilter(
        String q,
        String make,
        FuelType fuelType,
        Transmission transmission,
        Origin origin,
        Integer yearFrom,
        Integer yearTo,
        BigDecimal priceFrom,
        BigDecimal priceTo,
        Integer mileageTo
) {
}
