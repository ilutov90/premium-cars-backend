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
 * @param profitEur          our markup on this car (called profit before October 31, 2026)
 * @param profitOverridden   the markup was set for this car instead of taken from the formula
 * @param profitPct          the 021 formula's profit percent of FOB (only prices calculated with it)
 * @param profitMinEur       the 021 formula's least profit (only prices calculated with it)
 * @param calculatedTotalEur what the formula gives (null when it cannot be calculated)
 * @param totalEur           the final price: {@code calculatedTotalEur}, or the price set by hand
 * @param fixedTotal         {@code totalEur} was set by hand
 * @param eurPerUnit         the exchange rate used: EUR per 1 unit of {@code sourceCurrency} (null when not calculated)
 * @param stepEur            the step: {@code stepBaseEur} + {@code stepPct} % of the FOB above {@code stepFromEur}
 *                           (null before 027)
 * @param markupThresholdEur the FOB up to which {@code markupLowEur} applies, {@code markupHighEur} above (null when
 *                           the markup was set for this car, or before 027)
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
        BigDecimal eurPerUnit,
        BigDecimal profitPct,
        BigDecimal profitMinEur,
        BigDecimal stepEur,
        BigDecimal stepBaseEur,
        BigDecimal stepFromEur,
        BigDecimal stepPct,
        BigDecimal markupLowEur,
        BigDecimal markupHighEur,
        BigDecimal markupThresholdEur
) {

    /** The same breakdown with another final price. */
    public PriceBreakdown withTotal(BigDecimal total) {
        return new PriceBreakdown(profile, sourcePrice, sourceCurrency, carPriceEur, seaFreightEur, landTransportEur,
                commissionEur, dutyVatPct, dutyVatEur, baseCostsEur, steps, stepCostsEur, profitEur,
                profitOverridden, calculatedTotalEur, total, fixedTotal, eurPerUnit, profitPct, profitMinEur, stepEur,
                stepBaseEur, stepFromEur, stepPct, markupLowEur, markupHighEur, markupThresholdEur);
    }
}
