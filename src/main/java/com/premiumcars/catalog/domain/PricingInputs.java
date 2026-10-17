package com.premiumcars.catalog.domain;

import java.math.BigDecimal;
import java.util.Locale;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * What the final price is calculated from (admin only).
 *
 * @param sourcePrice    price on the source site, in {@code sourceCurrency}
 * @param shippingEur    per-car override; null = default from configuration
 * @param marginEur      per-car override; null = default from configuration
 * @param extraDutyRate  duty on top of the standard rate (e.g. EU duty on Chinese EVs), as a fraction; null = 0
 */
public record PricingInputs(
        @DecimalMin("0") BigDecimal sourcePrice,
        @Size(min = 3, max = 3) String sourceCurrency,
        @DecimalMin("0") BigDecimal shippingEur,
        @DecimalMin("0") BigDecimal marginEur,
        @DecimalMin("0") @DecimalMax("2") BigDecimal extraDutyRate
) {

    public static final PricingInputs EMPTY = new PricingInputs(null, null, null, null, null);

    public PricingInputs {
        sourceCurrency = sourceCurrency == null || sourceCurrency.isBlank()
                ? null : sourceCurrency.trim().toUpperCase(Locale.ROOT);
    }

    public PricingInputs withSourcePrice(BigDecimal price, String currency) {
        return new PricingInputs(price, currency, shippingEur, marginEur, extraDutyRate);
    }
}
