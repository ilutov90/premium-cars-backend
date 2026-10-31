package com.premiumcars.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One site's price formula, all amounts in EUR, on the car's FOB price as the site shows it (converted to EUR):
 *
 * <pre>
 *   duty and VAT = from the table by FOB when the formula has one (autowini), else dutyVatPct % of (FOB + sea freight)
 *   markup       = markupLow up to markupThreshold FOB (inclusive), markupHigh above it
 *   final price  = FOB + sea freight + land transport + commission + duty and VAT + markup
 * </pre>
 *
 * Korea (autowini), FOB 15 700: 15 700 + 1200 + 1600 + 500 + 2680 (the 16 000 row) + 1000 = 22 680.
 * China (autohome, guazi), FOB 21 450: 21 450 + 2000 + 1600 + 500 + 20 % of 23 450 (4690) + 1000 = 31 240.
 *
 * @param seaFreight      shipping to the European port
 * @param landTransport   from the port to Bulgaria
 * @param commission      the buying agent's commission
 * @param dutyVatPct      customs duty and VAT together, percent of FOB + sea freight (20 = 20 %); unused with a table
 * @param dutyBrackets    the duty and VAT table by FOB, ascending, the open row ({@code fobUpTo} null) last; empty =
 *                        the percent
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
        @Size(max = 200) List<@Valid @NotNull DutyBracket> dutyBrackets,
        @NotNull @DecimalMin("0") BigDecimal markupLow,
        @NotNull @DecimalMin("0") BigDecimal markupHigh,
        @NotNull @DecimalMin("0") BigDecimal markupThreshold,
        Instant updatedAt
) {

    public PricingProfile {
        dutyBrackets = dutyBrackets == null ? List.of() : List.copyOf(dutyBrackets);
    }

    public PricingProfile withBrackets(List<DutyBracket> brackets) {
        return new PricingProfile(id, seaFreight, landTransport, commission, dutyVatPct, brackets, markupLow,
                markupHigh, markupThreshold, updatedAt);
    }
}
