package com.premiumcars.pricing;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * One site's price formula, all amounts in EUR:
 *
 * <pre>
 *   final price = car price + baseCosts + stepCost * steps(car price - threshold, step) + profit
 * </pre>
 *
 * e.g. autohome: 5860 + 2880 for every 10 000 above 10 000 + 2000 profit.
 *
 * @param baseCosts  all costs of one import (transport, customs, fees …) for a car up to {@code threshold}
 * @param threshold  car price from which {@code stepCost} starts to apply
 * @param step       size of one step above the threshold (10 000)
 * @param stepCost   extra costs per step
 * @param stepMode   how partial steps count
 * @param profit     our profit per car
 * @param updatedAt  ignored on update
 */
public record PricingProfile(
        PriceProfile id,
        @NotNull @DecimalMin("0") BigDecimal baseCosts,
        @NotNull @DecimalMin("0") BigDecimal threshold,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal step,
        @NotNull @DecimalMin("0") BigDecimal stepCost,
        @NotNull StepMode stepMode,
        @NotNull @DecimalMin("0") BigDecimal profit,
        Instant updatedAt
) {
}
