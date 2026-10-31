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
 *   step         = step base + step % of the FOB above "step from"
 *   markup       = markup low up to the threshold FOB, markup high above it
 *   final price  = FOB + sea freight + land transport + commission + duty and VAT + step + markup
 * </pre>
 *
 * rounded to whole euros. Per car, the admin may set another markup or the final price by hand.
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
     * @param profitOverride markup for this car instead of the profile's (null = the profile's)
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
                    profitOverride != null, null, fixed, true, null, null, null, null, null, null, null, null, null,
                    null);
        }
        String currency = sourceCurrency == null ? "EUR" : sourceCurrency;
        BigDecimal rate = rates.eurPerUnit(currency);
        BigDecimal fob = round(sourcePrice.multiply(rate));
        BigDecimal markup = profitOverride == null ? markup(fob, profile) : profitOverride;

        BigDecimal dutyVat = round(fob.add(profile.seaFreight()).multiply(profile.dutyVatPct()).divide(HUNDRED));
        BigDecimal step = step(fob, profile);
        BigDecimal calculated = whole(fob.add(profile.seaFreight()).add(profile.landTransport())
                .add(profile.commission()).add(dutyVat).add(step).add(markup));

        boolean own = profitOverride != null;
        return new PriceBreakdown(profile.id(), sourcePrice, currency, fob, round(profile.seaFreight()),
                round(profile.landTransport()), round(profile.commission()), plain(profile.dutyVatPct()), dutyVat,
                null, null, null, round(markup), own, calculated, fixed == null ? calculated : fixed, fixed != null,
                rate, null, null, step, round(profile.stepBase()), round(profile.stepFrom()),
                plain(profile.stepPct()), own ? null : round(profile.markupLow()),
                own ? null : round(profile.markupHigh()), own ? null : round(profile.markupThreshold()));
    }

    /** The markup: low up to the threshold FOB (inclusive), high above it (25 000: 1000, 25 000.01: 1500). */
    static BigDecimal markup(BigDecimal fob, PricingProfile profile) {
        return round(fob.compareTo(profile.markupThreshold()) <= 0 ? profile.markupLow() : profile.markupHigh());
    }

    /** The step: its base, plus its percent of the FOB above "step from" (1700 + 10 % of 17 000 - 10 000 = 2400). */
    static BigDecimal step(BigDecimal fob, PricingProfile profile) {
        BigDecimal above = fob.subtract(profile.stepFrom()).max(BigDecimal.ZERO);
        return round(profile.stepBase().add(above.multiply(profile.stepPct()).divide(HUNDRED)));
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
