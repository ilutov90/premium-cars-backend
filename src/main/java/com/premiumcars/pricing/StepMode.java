package com.premiumcars.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** How the amount above the threshold is turned into a number of steps. */
public enum StepMode {
    /** Every started step counts: 0.1 step -> 1. */
    STARTED,
    /** Only full steps count: 1.9 steps -> 1. */
    FULL,
    /** Proportional: 1.5 steps -> 1.5. */
    PROPORTIONAL;

    private static final int SCALE = 6;

    BigDecimal steps(BigDecimal amountAbove, BigDecimal step) {
        if (amountAbove.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return switch (this) {
            case STARTED -> amountAbove.divide(step, 0, RoundingMode.CEILING);
            case FULL -> amountAbove.divide(step, 0, RoundingMode.FLOOR);
            case PROPORTIONAL -> amountAbove.divide(step, SCALE, RoundingMode.HALF_UP);
        };
    }
}
