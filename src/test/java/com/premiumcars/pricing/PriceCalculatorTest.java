package com.premiumcars.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriceCalculatorTest {

    private final PriceCalculator calculator = new PriceCalculator(
            currency -> "USD".equals(currency) ? new BigDecimal("0.86") : BigDecimal.ONE);

    private static final PricingProfile AUTOHOME = new PricingProfile(PriceProfile.AUTOHOME, new BigDecimal("5860"),
            new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("2880"), new BigDecimal("2000"), null);

    private static final PricingProfile AUTOWINI = new PricingProfile(PriceProfile.AUTOWINI, new BigDecimal("5000"),
            new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("600"), new BigDecimal("2000"), null);

    @ParameterizedTest(name = "autohome {0} EUR -> {1}")
    @CsvSource({
            // car, total = car + 5860 + 2880 * (car - 10000) / 10000 + 2000
            "8000,   15860",   // up to 10 000: fixed costs only
            "10000,  17860",
            "15000,  24300",   // + 1440 (half a step)
            "20000,  30740",   // + 2880
            "25000,  37180",   // + 2880 + 1440: the example from the business
            "40000,  56500",   // + 3 * 2880
    })
    void autohomeIsProportionalAboveTheThreshold(String car, String total) {
        assertThat(calculator.calculate(new BigDecimal(car), "EUR", AUTOHOME).totalEur()).isEqualByComparingTo(total);
    }

    @Test
    void autowiniFormulaWithConversion() {
        // 30000 USD * 0.86 = 25800 EUR; 15800 above 10000 = 1.58 steps * 600 = 948
        PriceBreakdown b = calculator.calculate(new BigDecimal("30000"), "USD", AUTOWINI);

        assertThat(b.profile()).isEqualTo(PriceProfile.AUTOWINI);
        assertThat(b.carPriceEur()).isEqualByComparingTo("25800");
        assertThat(b.baseCostsEur()).isEqualByComparingTo("5000");
        assertThat(b.steps()).isEqualByComparingTo("1.58");
        assertThat(b.stepCostsEur()).isEqualByComparingTo("948");
        assertThat(b.profitEur()).isEqualByComparingTo("2000");
        assertThat(b.totalEur()).isEqualByComparingTo("33748");
    }

    @Test
    void anotherProfitForOneCar() {
        // 25000 + 5000 + 1.5 * 600 + 3500 (instead of the profile's 2000)
        PriceBreakdown b = calculator.calculate(new BigDecimal("25000"), "EUR", AUTOWINI, new BigDecimal("3500"), null);

        assertThat(b.profitEur()).isEqualByComparingTo("3500");
        assertThat(b.profitOverridden()).isTrue();
        assertThat(b.calculatedTotalEur()).isEqualByComparingTo("34400");
        assertThat(b.totalEur()).isEqualByComparingTo("34400");
        assertThat(b.fixedTotal()).isFalse();
    }

    @Test
    void finalPriceSetByHandWinsButTheCalculationStaysVisible() {
        PriceBreakdown b = calculator.calculate(new BigDecimal("25000"), "EUR", AUTOWINI, null, new BigDecimal("31999.6"));

        assertThat(b.calculatedTotalEur()).isEqualByComparingTo("32900");
        assertThat(b.totalEur()).isEqualByComparingTo("32000"); // whole euros
        assertThat(b.fixedTotal()).isTrue();
        assertThat(b.profitOverridden()).isFalse();
    }

    @Test
    void finalPriceSetByHandWorksWithoutASourcePrice() {
        PriceBreakdown b = calculator.calculate(null, null, AUTOWINI, null, new BigDecimal("28500"));

        assertThat(b.totalEur()).isEqualByComparingTo("28500");
        assertThat(b.fixedTotal()).isTrue();
        assertThat(b.carPriceEur()).isNull();
        assertThat(b.calculatedTotalEur()).isNull();
        assertThat(calculator.calculate(null, null, AUTOWINI, null, null)).isNull();
    }

    @Test
    void roundsToWholeEuros() {
        // 12345.67 + 5000 + 0.234567 * 600 (140.74) + 2000 = 19486.41 -> 19486
        PriceBreakdown b = calculator.calculate(new BigDecimal("12345.67"), "EUR", AUTOWINI);
        assertThat(b.steps()).isEqualByComparingTo("0.234567");
        assertThat(b.totalEur()).isEqualByComparingTo("19486");
        assertThat(b.totalEur().scale()).isEqualTo(2);
    }

    @Test
    void stepsAreNeverInScientificNotation() {
        PriceBreakdown b = calculator.calculate(new BigDecimal("110000"), "EUR", AUTOWINI);
        assertThat(b.steps().toString()).isEqualTo("10");
    }

    @Test
    void returnsNullWithoutPriceOrProfile() {
        assertThat(calculator.calculate(null, "USD", AUTOWINI)).isNull();
        assertThat(calculator.calculate(BigDecimal.TEN, "USD", null)).isNull();
    }
}
