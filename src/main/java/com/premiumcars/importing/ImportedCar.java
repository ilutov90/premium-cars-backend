package com.premiumcars.importing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Transmission;

/** Raw data extracted from a source page, before it becomes a draft {@code Car}. Any field may be null. */
public record ImportedCar(
        String sourceRef,
        String title,
        String make,
        String model,
        String trim,
        Integer year,
        Integer mileageKm,
        BigDecimal price,
        String currency,
        FuelType fuelType,
        Transmission transmission,
        String bodyType,
        String engine,
        String color,
        String description,
        Map<String, String> specs,
        List<String> imageUrls
) {
}
