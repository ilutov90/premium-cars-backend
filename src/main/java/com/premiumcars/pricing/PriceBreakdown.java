package com.premiumcars.pricing;

import java.math.BigDecimal;

/**
 * How a car's final price was built (admin only; customers see only {@code totalEur}). Amounts in EUR.
 *
 * @param steps      steps above the threshold, proportional (1.5 = 15 000 above)
 * @param stepCosts  steps * step cost
 */
public record PriceBreakdown(
        PriceProfile profile,
        BigDecimal sourcePrice,
        String sourceCurrency,
        BigDecimal carPriceEur,
        BigDecimal baseCostsEur,
        BigDecimal steps,
        BigDecimal stepCostsEur,
        BigDecimal profitEur,
        BigDecimal totalEur
) {
}
