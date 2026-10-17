package com.premiumcars.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriceCalculatorTest {

    private final PriceCalculator calculator = new PriceCalculator(
            currency -> "USD".equals(currency) ? new BigDecimal("0.86") : BigDecimal.ONE);

    private static PricingProfile autohome(StepMode mode) {
        return new PricingProfile(PriceProfile.AUTOHOME, new BigDecimal("5860"), new BigDecimal("10000"),
                new BigDecimal("10000"), new BigDecimal("2880"), mode, new BigDecimal("2000"), null);
    }

    private static PricingProfile autowini(StepMode mode) {
        return new PricingProfile(PriceProfile.AUTOWINI, new BigDecimal("5000"), new BigDecimal("10000"),
                new BigDecimal("10000"), new BigDecimal("600"), mode, new BigDecimal("2000"), null);
    }

    @ParameterizedTest(name = "autohome {0} EUR, {1} -> {2}")
    @CsvSource({
            // car,  mode,          total = car + 5860 + 2880 * steps + 2000
            "8000,   STARTED,       15860",   // below the threshold: no steps
            "10000,  STARTED,       17860",   // exactly at the threshold: no steps
            "10001,  STARTED,       20741",   // 1 started step
            "20000,  STARTED,       30740",   // exactly 1 step
            "25000,  STARTED,       38620",   // 2 started steps
            "25000,  FULL,          35740",   // 1 full step
            "25000,  PROPORTIONAL,  37180",   // 1.5 steps = 4320
    })
    void autohomeFormula(String car, StepMode mode, String total) {
        PriceBreakdown b = calculator.calculate(new BigDecimal(car), "EUR", autohome(mode));
        assertThat(b.totalEur()).isEqualByComparingTo(total);
    }

    @Test
    void autowiniFormulaWithConversion() {
        // 30000 USD * 0.86 = 25800 EUR; 15800 above 10000 = 2 started steps of 600
        PriceBreakdown b = calculator.calculate(new BigDecimal("30000"), "USD", autowini(StepMode.STARTED));

        assertThat(b.profile()).isEqualTo(PriceProfile.AUTOWINI);
        assertThat(b.carPriceEur()).isEqualByComparingTo("25800");
        assertThat(b.baseCostsEur()).isEqualByComparingTo("5000");
        assertThat(b.steps()).isEqualByComparingTo("2");
        assertThat(b.stepCostsEur()).isEqualByComparingTo("1200");
        assertThat(b.profitEur()).isEqualByComparingTo("2000");
        assertThat(b.totalEur()).isEqualByComparingTo("34000");
    }

    @Test
    void roundsToWholeEuros() {
        // 12345.67 + 5000 + 0.234567 * 600 (140.74) + 2000 = 19486.41 -> 19486
        PriceBreakdown b = calculator.calculate(new BigDecimal("12345.67"), "EUR", autowini(StepMode.PROPORTIONAL));
        assertThat(b.steps()).isEqualByComparingTo("0.234567");
        assertThat(b.totalEur()).isEqualByComparingTo("19486");
        assertThat(b.totalEur().scale()).isEqualTo(2);
    }

    @Test
    void stepsAreNeverInScientificNotation() {
        PriceBreakdown b = calculator.calculate(new BigDecimal("110000"), "EUR", autowini(StepMode.STARTED));
        assertThat(b.steps().toString()).isEqualTo("10");
    }

    @Test
    void returnsNullWithoutPriceOrProfile() {
        assertThat(calculator.calculate(null, "USD", autowini(StepMode.STARTED))).isNull();
        assertThat(calculator.calculate(BigDecimal.TEN, "USD", null)).isNull();
    }
}
