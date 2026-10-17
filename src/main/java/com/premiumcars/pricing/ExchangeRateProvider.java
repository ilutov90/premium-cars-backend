package com.premiumcars.pricing;

import java.math.BigDecimal;

/**
 * Converts source currencies to EUR. The MVP reads fixed rates from configuration;
 * later replace it with an implementation that refreshes daily from the ECB.
 */
public interface ExchangeRateProvider {

    /** @return how many EUR one unit of {@code currency} is worth */
    BigDecimal eurPerUnit(String currency);
}
