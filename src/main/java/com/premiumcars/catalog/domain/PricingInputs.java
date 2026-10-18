package com.premiumcars.catalog.domain;

import java.math.BigDecimal;
import java.util.Locale;

import com.premiumcars.pricing.PriceProfile;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * What the final price is calculated from (admin only).
 *
 * @param sourcePrice     price on the source site, in {@code sourceCurrency}
 * @param sourceCurrency  ISO code (USD, CNY, KRW, EUR)
 * @param profile         price formula; null = the formula of the car's source site
 * @param profitEur       profit for this car instead of the formula's; null = the formula's
 * @param fixedTotalEur   final price set by hand, replacing the calculation; null = calculated
 */
public record PricingInputs(
        @DecimalMin("0") BigDecimal sourcePrice,
        @Size(min = 3, max = 3) String sourceCurrency,
        PriceProfile profile,
        @DecimalMin("0") BigDecimal profitEur,
        @DecimalMin("0") BigDecimal fixedTotalEur
) {

    public static final PricingInputs EMPTY = new PricingInputs(null, null, null, null, null);

    public PricingInputs {
        sourceCurrency = sourceCurrency == null || sourceCurrency.isBlank()
                ? null : sourceCurrency.trim().toUpperCase(Locale.ROOT);
    }

    public PricingInputs withSourcePrice(BigDecimal price, String currency) {
        return new PricingInputs(price, currency, profile, profitEur, fixedTotalEur);
    }

    public PricingInputs withProfile(PriceProfile value) {
        return new PricingInputs(sourcePrice, sourceCurrency, value, profitEur, fixedTotalEur);
    }
}
