package com.premiumcars.pricing;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * One site's price formula, all amounts in EUR, on the car's FOB price as the site shows it (converted to EUR):
 *
 * <pre>
 *   duty and VAT = dutyVatPct % of (FOB + sea freight)
 *   final price  = FOB + sea freight + land transport + commission + duty and VAT + profit
 * </pre>
 *
 * China (autohome, guazi), FOB 21 450: 21 450 + 2000 + 1600 + 500 + 20 % of 23 450 (4690) + 2000 = 32 240.
 *
 * @param seaFreight    shipping to the European port
 * @param landTransport from the port to Bulgaria
 * @param commission    the buying agent's commission
 * @param profit        our profit per car (a car may have its own)
 * @param dutyVatPct    customs duty and VAT together, percent of FOB + sea freight (20 = 20 %)
 * @param updatedAt     ignored on update
 */
public record PricingProfile(
        PriceProfile id,
        @NotNull @DecimalMin("0") BigDecimal seaFreight,
        @NotNull @DecimalMin("0") BigDecimal landTransport,
        @NotNull @DecimalMin("0") BigDecimal commission,
        @NotNull @DecimalMin("0") BigDecimal profit,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal dutyVatPct,
        Instant updatedAt
) {
}
