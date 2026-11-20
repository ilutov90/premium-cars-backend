package com.premiumcars.order;

import java.math.BigDecimal;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarSource;

/**
 * The car as it was when the order was made (or got this car): kept in the order, so its history outlives the car.
 *
 * @param priceEur the site's final price then
 */
public record CarSnapshot(String title, String slug, String make, String model, Integer year, Integer mileageKm,
                          CarSource source, String sourceUrl, String sourceRef, BigDecimal priceEur) {

    public static CarSnapshot of(Car c) {
        var s = c.specs();
        return new CarSnapshot(s.title(), c.slug(), s.make(), s.model(), s.year(), s.mileageKm(), c.source(),
                c.sourceUrl(), c.sourceRef(), c.totalPriceEur());
    }
}
