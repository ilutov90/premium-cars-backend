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
 */
public record PricingInputs(
        @DecimalMin("0") BigDecimal sourcePrice,
        @Size(min = 3, max = 3) String sourceCurrency,
        PriceProfile profile
) {

    public static final PricingInputs EMPTY = new PricingInputs(null, null, null);

    public PricingInputs {
        sourceCurrency = sourceCurrency == null || sourceCurrency.isBlank()
                ? null : sourceCurrency.trim().toUpperCase(Locale.ROOT);
    }

    public PricingInputs withSourcePrice(BigDecimal price, String currency) {
        return new PricingInputs(price, currency, profile);
    }

    public PricingInputs withProfile(PriceProfile value) {
        return new PricingInputs(sourcePrice, sourceCurrency, value);
    }
}
