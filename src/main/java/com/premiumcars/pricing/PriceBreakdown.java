package com.premiumcars.pricing;

import java.math.BigDecimal;

/**
 * Landed cost in Bulgaria, all amounts in EUR.
 * total = carPrice + shipping + customsDuty + vat + fees + margin
 */
public record PriceBreakdown(
        BigDecimal sourcePrice,
        String sourceCurrency,
        BigDecimal carPriceEur,
        BigDecimal shippingEur,
        BigDecimal customsDutyRate,
        BigDecimal customsDutyEur,
        BigDecimal vatRate,
        BigDecimal vatEur,
        BigDecimal feesEur,
        BigDecimal marginEur,
        BigDecimal totalEur
) {
}
