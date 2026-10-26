package com.premiumcars.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * Final customer price of a car (see {@link PricingProfile}):
 *
 * <pre>
 *   FOB in EUR   = source price * exchange rate
 *   duty and VAT = duty/VAT % of (FOB + sea freight)
 *   final price  = FOB + sea freight + land transport + commission + duty and VAT + profit
 * </pre>
 *
 * rounded to whole euros. Per car, the admin may set another profit or the final price by hand.
 * The same number is shown on the site and in the Facebook/Instagram/Viber post.
 */
@Component
public class PriceCalculator {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ExchangeRateProvider rates;

    public PriceCalculator(ExchangeRateProvider rates) {
        this.rates = rates;
    }

    /** The formula alone (no per-car corrections). @return null when the source price or the profile is unknown */
    public PriceBreakdown calculate(BigDecimal sourcePrice, String sourceCurrency, PricingProfile profile) {
        return calculate(sourcePrice, sourceCurrency, profile, null, null);
    }

    /**
     * @param profitOverride profit for this car instead of the profile's (null = the profile's)
     * @param fixedTotal     final price set by hand (null = calculated); used even when nothing can be calculated
     * @return null when nothing can be calculated and no price was set by hand
     */
    public PriceBreakdown calculate(BigDecimal sourcePrice, String sourceCurrency, PricingProfile profile,
                                    BigDecimal profitOverride, BigDecimal fixedTotal) {
        BigDecimal fixed = fixedTotal == null ? null : whole(fixedTotal);
        if (sourcePrice == null || profile == null) {
            if (fixed == null) {
                return null;
            }
            return new PriceBreakdown(profile == null ? null : profile.id(), sourcePrice, sourceCurrency, null, null,
                    null, null, null, null, null, null, null, profitOverride == null ? null : round(profitOverride),
                    profitOverride != null, null, fixed, true, null);
        }
        String currency = sourceCurrency == null ? "EUR" : sourceCurrency;
        BigDecimal rate = rates.eurPerUnit(currency);
        BigDecimal fob = round(sourcePrice.multiply(rate));
        BigDecimal profit = profitOverride == null ? profile.profit() : profitOverride;

        BigDecimal dutyVat = round(fob.add(profile.seaFreight()).multiply(profile.dutyVatPct()).divide(HUNDRED));
        BigDecimal calculated = whole(fob.add(profile.seaFreight()).add(profile.landTransport())
                .add(profile.commission()).add(dutyVat).add(profit));

        return new PriceBreakdown(profile.id(), sourcePrice, currency, fob, round(profile.seaFreight()),
                round(profile.landTransport()), round(profile.commission()), plain(profile.dutyVatPct()), dutyVat,
                null, null, null, round(profit), profitOverride != null, calculated,
                fixed == null ? calculated : fixed, fixed != null, rate);
    }

    /** Rounded to whole euros, kept with two decimals like the other amounts. */
    private static BigDecimal whole(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** 20.00 -> 20, 17.50 -> 17.5 (never 2E+1). */
    private static BigDecimal plain(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0, RoundingMode.UNNECESSARY) : v;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
