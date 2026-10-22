package com.premiumcars.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * Final customer price of a car (see {@link PricingProfile}):
 *
 * <pre>
 *   car price in EUR = source price * exchange rate
 *   steps            = max(0, car price - threshold) / step     (proportional: 5000 above = 0.5 steps)
 *   final price      = car price + base costs + step cost * steps + profit
 * </pre>
 *
 * rounded to whole euros. Per car, the admin may set another profit or the final price by hand.
 * The same number is shown on the site and in the Facebook/Instagram/Viber post.
 */
@Component
public class PriceCalculator {

    private static final int SCALE = 2;
    private static final int STEP_SCALE = 6;

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
                    null, null, profitOverride == null ? null : round(profitOverride), profitOverride != null, null,
                    fixed, true, null);
        }
        String currency = sourceCurrency == null ? "EUR" : sourceCurrency;
        BigDecimal rate = rates.eurPerUnit(currency);
        BigDecimal carPriceEur = round(sourcePrice.multiply(rate));
        BigDecimal profit = profitOverride == null ? profile.profit() : profitOverride;

        BigDecimal above = carPriceEur.subtract(profile.threshold()).max(BigDecimal.ZERO);
        BigDecimal steps = above.divide(profile.step(), STEP_SCALE, RoundingMode.HALF_UP);
        BigDecimal stepCosts = round(profile.stepCost().multiply(steps));
        BigDecimal calculated = whole(carPriceEur.add(profile.baseCosts()).add(stepCosts).add(profit));

        return new PriceBreakdown(profile.id(), sourcePrice, currency, carPriceEur, round(profile.baseCosts()),
                plain(steps), stepCosts, round(profit), profitOverride != null, calculated,
                fixed == null ? calculated : fixed, fixed != null, rate);
    }

    /** Rounded to whole euros, kept with two decimals like the other amounts. */
    private static BigDecimal whole(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** 2.000000 -> 2, 1.500000 -> 1.5 (never 1E+1). */
    private static BigDecimal plain(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0, RoundingMode.UNNECESSARY) : v;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
