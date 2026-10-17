package com.premiumcars.importing;

import java.math.BigDecimal;
import java.util.List;

import com.premiumcars.catalog.domain.CarSpecs;

/**
 * Data extracted from a source page, before it becomes a draft car.
 *
 * @param sourceRef      listing id on the source site (e.g. "60182282", "IC4960158")
 * @param sourceLocation city/region at the source (admin only)
 * @param price          price on the page, or null when not shown
 * @param currency       currency of {@code price}
 * @param warnings       things the admin should check, e.g. "battery capacity not found"
 */
public record ImportedCar(
        String sourceRef,
        String sourceLocation,
        CarSpecs specs,
        BigDecimal price,
        String currency,
        List<String> imageUrls,
        List<String> warnings
) {

    public ImportedCar {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
