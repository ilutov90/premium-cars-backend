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
 *   step         = stepBase + stepPct % of the FOB above stepFrom
 *   markup       = markupLow up to markupThreshold FOB (inclusive), markupHigh above it
 *   final price  = FOB + sea freight + land transport + commission + duty and VAT + step + markup
 * </pre>
 *
 * Korea (autowini), FOB 17 000: 17 000 + 1200 + 1600 + 500 + step 2400 (1700 + 10 % of 7000) + 1000 = 23 700.
 * China (autohome, guazi), FOB 21 450: 21 450 + 2000 + 1600 + 500 + 20 % of 23 450 (4690) + 1000 = 31 240.
 *
 * @param seaFreight      shipping to the European port
 * @param landTransport   from the port to Bulgaria
 * @param commission      the buying agent's commission
 * @param dutyVatPct      customs duty and VAT together, percent of FOB + sea freight (20 = 20 %; autowini 0)
 * @param stepBase        the step's fixed part (autowini 1700; 0 = no step)
 * @param stepFrom        the FOB from which the step's percent counts (10 000)
 * @param stepPct         percent of the FOB above {@code stepFrom} (10 = 10 %)
 * @param markupLow       our markup per car up to {@code markupThreshold} FOB (a car may have its own amount)
 * @param markupHigh      our markup per car above it
 * @param markupThreshold FOB in EUR that separates the two markups
 * @param updatedAt       ignored on update
 */
public record PricingProfile(
        PriceProfile id,
        @NotNull @DecimalMin("0") BigDecimal seaFreight,
        @NotNull @DecimalMin("0") BigDecimal landTransport,
        @NotNull @DecimalMin("0") BigDecimal commission,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal dutyVatPct,
        @NotNull @DecimalMin("0") BigDecimal stepBase,
        @NotNull @DecimalMin("0") BigDecimal stepFrom,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal stepPct,
        @NotNull @DecimalMin("0") BigDecimal markupLow,
        @NotNull @DecimalMin("0") BigDecimal markupHigh,
        @NotNull @DecimalMin("0") BigDecimal markupThreshold,
        Instant updatedAt
) {
}
