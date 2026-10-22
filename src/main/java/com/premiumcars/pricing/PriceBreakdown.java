package com.premiumcars.pricing;

import java.math.BigDecimal;

/**
 * How a car's final price was built (admin only; customers see only {@code totalEur}). Amounts in EUR.
 * When the price was set by hand without a source price, the calculated parts are null.
 *
 * @param steps              steps above the threshold, proportional (1.5 = 15 000 above)
 * @param stepCostsEur       steps * step cost
 * @param profitOverridden   the profit was set for this car instead of taken from the formula
 * @param calculatedTotalEur what the formula gives (null when it cannot be calculated)
 * @param totalEur           the final price: {@code calculatedTotalEur}, or the price set by hand
 * @param fixedTotal         {@code totalEur} was set by hand
 * @param eurPerUnit         the exchange rate used: EUR per 1 unit of {@code sourceCurrency} (null when not calculated)
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
        boolean profitOverridden,
        BigDecimal calculatedTotalEur,
        BigDecimal totalEur,
        boolean fixedTotal,
        BigDecimal eurPerUnit
) {
}
