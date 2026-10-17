package com.premiumcars.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * Final customer price of a car (see {@link PricingProfile}):
 *
 * <pre>
 *   car price in EUR = source price * exchange rate
 *   final price      = car price + base costs + step cost * steps above the threshold + profit
 * </pre>
 *
 * rounded to whole euros. The same number is shown on the site and in the Facebook/Instagram post.
 */
@Component
public class PriceCalculator {

    private static final int SCALE = 2;

    private final ExchangeRateProvider rates;

    public PriceCalculator(ExchangeRateProvider rates) {
        this.rates = rates;
    }

    /** @return null when the source price or the profile is unknown */
    public PriceBreakdown calculate(BigDecimal sourcePrice, String sourceCurrency, PricingProfile profile) {
        if (sourcePrice == null || profile == null) {
            return null;
        }
        String currency = sourceCurrency == null ? "EUR" : sourceCurrency;
        BigDecimal carPriceEur = round(sourcePrice.multiply(rates.eurPerUnit(currency)));

        BigDecimal steps = profile.stepMode().steps(carPriceEur.subtract(profile.threshold()), profile.step());
        BigDecimal stepCosts = round(profile.stepCost().multiply(steps));
        BigDecimal total = carPriceEur.add(profile.baseCosts()).add(stepCosts).add(profile.profit())
                .setScale(0, RoundingMode.HALF_UP)
                .setScale(SCALE, RoundingMode.UNNECESSARY);

        return new PriceBreakdown(profile.id(), sourcePrice, currency, carPriceEur, round(profile.baseCosts()),
                plain(steps), stepCosts, round(profile.profit()), total);
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
