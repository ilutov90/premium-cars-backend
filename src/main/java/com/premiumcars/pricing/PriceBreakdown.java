package com.premiumcars.pricing;

import java.math.BigDecimal;

/**
 * How a car's final price was built (admin only; customers see only {@code totalEur}). Amounts in EUR.
 * When the price was set by hand without a source price, the calculated parts are null.
 * <p>
 * Kept with the car as calculated (see 013-frozen-prices.sql). Prices calculated before October 2026 used the old
 * formula (base costs + proportional step costs): their breakdown has {@code baseCostsEur}, {@code steps} and
 * {@code stepCostsEur} and none of the new parts, until an admin recalculates the car.
 *
 * @param carPriceEur        the FOB price in EUR (source price × exchange rate)
 * @param dutyVatPct         duty and VAT percent, of FOB + sea freight
 * @param dutyVatEur         duty and VAT amount
 * @param baseCostsEur       old formula only
 * @param steps              old formula only: steps above the threshold, proportional
 * @param stepCostsEur       old formula only
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
        BigDecimal seaFreightEur,
        BigDecimal landTransportEur,
        BigDecimal commissionEur,
        BigDecimal dutyVatPct,
        BigDecimal dutyVatEur,
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

    /** The same breakdown with another final price. */
    public PriceBreakdown withTotal(BigDecimal total) {
        return new PriceBreakdown(profile, sourcePrice, sourceCurrency, carPriceEur, seaFreightEur, landTransportEur,
                commissionEur, dutyVatPct, dutyVatEur, baseCostsEur, steps, stepCostsEur, profitEur,
                profitOverridden, calculatedTotalEur, total, fixedTotal, eurPerUnit);
    }
}
