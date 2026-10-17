package com.premiumcars.catalog.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The editable part of a car: description and pricing inputs.
 * Written as a whole on import and on every admin save.
 *
 * @param shippingEur   per-car override; null = default from configuration
 * @param marginEur     per-car override; null = default from configuration
 * @param extraDutyRate duty on top of the standard rate (e.g. EU duty on Chinese EVs), as a fraction; null = 0
 */
public record CarDetails(
        String title,
        String make,
        String model,
        String trim,
        Integer year,
        Integer mileageKm,
        FuelType fuelType,
        Transmission transmission,
        String bodyType,
        String engine,
        Integer powerHp,
        String color,
        String description,
        Map<String, String> specs,
        BigDecimal sourcePrice,
        String sourceCurrency,
        BigDecimal shippingEur,
        BigDecimal marginEur,
        BigDecimal extraDutyRate
) {

    public CarDetails {
        specs = specs == null ? Map.of() : specs;
    }
}
