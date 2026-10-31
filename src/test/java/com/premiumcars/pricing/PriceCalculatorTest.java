package com.premiumcars.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriceCalculatorTest {

    private final PriceCalculator calculator = new PriceCalculator(
            currency -> "USD".equals(currency) ? new BigDecimal("0.86") : BigDecimal.ONE);

    /** China (autohome, guazi): sea 2000, land 1600, commission 500, duty and VAT 20 %, no step, markup 1000 / 1500. */
    private static final PricingProfile CHINA = profile(PriceProfile.AUTOHOME, "2000", "20", "0", "0", "0");

    /** Korea (autowini): sea 1200, land 1600, commission 500, no duty, step 1700 + 10 % over 10 000, markup 1000 / 1500. */
    private static final PricingProfile KOREA = profile(PriceProfile.AUTOWINI, "1200", "0", "1700", "10000", "10");

    private static PricingProfile profile(PriceProfile id, String sea, String dutyVat, String stepBase,
                                          String stepFrom, String stepPct) {
        return new PricingProfile(id, new BigDecimal(sea), new BigDecimal("1600"), new BigDecimal("500"),
                new BigDecimal(dutyVat), new BigDecimal(stepBase), new BigDecimal(stepFrom), new BigDecimal(stepPct),
                new BigDecimal("1000"), new BigDecimal("1500"), new BigDecimal("25000"), null);
    }

    @Test
    void chinaTheBusinessExample() {
        // 21450 + 2000 + 1600 + 500 + (21450 + 2000) * 0.20 = 4690, + markup 1000 (FOB up to 25 000)
        PriceBreakdown b = calculator.calculate(new BigDecimal("21450"), "EUR", CHINA);

        assertThat(b.profile()).isEqualTo(PriceProfile.AUTOHOME);
        assertThat(b.carPriceEur()).isEqualByComparingTo("21450");
        assertThat(b.seaFreightEur()).isEqualByComparingTo("2000");
        assertThat(b.landTransportEur()).isEqualByComparingTo("1600");
        assertThat(b.commissionEur()).isEqualByComparingTo("500");
        assertThat(b.dutyVatPct().toString()).isEqualTo("20");
        assertThat(b.dutyVatEur()).isEqualByComparingTo("4690");
        assertThat(b.stepEur()).isEqualByComparingTo("0");
        assertThat(b.profitEur()).isEqualByComparingTo("1000");
        assertThat(b.markupThresholdEur()).isEqualByComparingTo("25000");
        assertThat(b.profitPct()).isNull();
        assertThat(b.baseCostsEur()).isNull();
        assertThat(b.totalEur()).isEqualByComparingTo("31240");
    }

    @ParameterizedTest(name = "korea {0} EUR -> {1}")
    @CsvSource({
            // fob, total = fob + 1200 + 1600 + 500 + step (1700 + 10 % over 10 000) + markup (1000 up to 25 000, else 1500)
            "8000,   14000", // step 1700
            "10000,  16000", // step 1700
            "17000,  23700", // step 1700 + 700 (the business example)
            "23000,  30300", // step 1700 + 1300
            "25000,  32500", // step 3200, markup 1000 (25 000 inclusive)
            "25001,  33001.1", // step 3200.1, markup 1500: 33001.1 rounds to 33001
    })
    void koreaFormula(String fob, String total) {
        assertThat(calculator.calculate(new BigDecimal(fob), "EUR", KOREA).totalEur())
                .isEqualByComparingTo(new BigDecimal(total).setScale(0, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void koreaTheBusinessExample() {
        PriceBreakdown b = calculator.calculate(new BigDecimal("17000"), "EUR", KOREA);

        assertThat(b.dutyVatEur()).isEqualByComparingTo("0");
        assertThat(b.stepEur()).isEqualByComparingTo("2400");
        assertThat(b.stepBaseEur()).isEqualByComparingTo("1700");
        assertThat(b.stepFromEur()).isEqualByComparingTo("10000");
        assertThat(b.stepPct().toString()).isEqualTo("10");
        assertThat(b.profitEur()).isEqualByComparingTo("1000");
        assertThat(b.totalEur()).isEqualByComparingTo("23700");
    }

    @ParameterizedTest(name = "china {0} EUR -> {1}")
    @CsvSource({
            // fob, total = fob + 2000 + 1600 + 500 + 0.20 * (fob + 2000) + markup
            "8000,   15100",
            "25000,  35500", // markup 1000
            "30000,  42000", // markup 1500
    })
    void chinaFormula(String fob, String total) {
        assertThat(calculator.calculate(new BigDecimal(fob), "EUR", CHINA).totalEur()).isEqualByComparingTo(total);
    }

    @Test
    void usdIsConvertedFirst() {
        // 30000 USD * 0.86 = 25800 EUR; duty and VAT 0.20 * 27800 = 5560
        PriceBreakdown b = calculator.calculate(new BigDecimal("30000"), "USD", CHINA);

        assertThat(b.carPriceEur()).isEqualByComparingTo("25800");
        assertThat(b.eurPerUnit()).isEqualByComparingTo("0.86");
        assertThat(b.dutyVatEur()).isEqualByComparingTo("5560");
        assertThat(b.profitEur()).isEqualByComparingTo("1500"); // above 25 000 € FOB
        assertThat(b.totalEur()).isEqualByComparingTo("36960");
    }

    @Test
    void anotherProfitForOneCar() {
        // 14400 + 1200 + 1600 + 500 + step 2140 + 3500 (instead of the profile's 1000)
        PriceBreakdown b = calculator.calculate(new BigDecimal("14400"), "EUR", KOREA, new BigDecimal("3500"), null);

        assertThat(b.profitEur()).isEqualByComparingTo("3500");
        assertThat(b.profitOverridden()).isTrue();
        assertThat(b.markupThresholdEur()).isNull();
        assertThat(b.calculatedTotalEur()).isEqualByComparingTo("23340");
        assertThat(b.totalEur()).isEqualByComparingTo("23340");
        assertThat(b.fixedTotal()).isFalse();
    }

    @Test
    void finalPriceSetByHandWinsButTheCalculationStaysVisible() {
        PriceBreakdown b = calculator.calculate(new BigDecimal("14400"), "EUR", KOREA, null, new BigDecimal("21999.6"));

        assertThat(b.calculatedTotalEur()).isEqualByComparingTo("20840");
        assertThat(b.totalEur()).isEqualByComparingTo("22000"); // whole euros
        assertThat(b.fixedTotal()).isTrue();
        assertThat(b.stepEur()).isEqualByComparingTo("2140");
    }

    @Test
    void finalPriceSetByHandWorksWithoutASourcePrice() {
        PriceBreakdown b = calculator.calculate(null, null, KOREA, null, new BigDecimal("28500"));

        assertThat(b.totalEur()).isEqualByComparingTo("28500");
        assertThat(b.fixedTotal()).isTrue();
        assertThat(b.carPriceEur()).isNull();
        assertThat(b.dutyVatEur()).isNull();
        assertThat(b.calculatedTotalEur()).isNull();
        assertThat(calculator.calculate(null, null, KOREA, null, null)).isNull();
    }

    @Test
    void roundsToWholeEuros() {
        // 12345.12 + 1200 + 1600 + 500 + step 1700 + 0.10 * 2345.12 (1934.51) + 1000 = 18579.63 -> 18580
        PriceBreakdown b = calculator.calculate(new BigDecimal("12345.12"), "EUR", KOREA);
        assertThat(b.stepEur()).isEqualByComparingTo("1934.51");
        assertThat(b.totalEur()).isEqualByComparingTo("18580");
        assertThat(b.totalEur().scale()).isEqualTo(2);
    }

    @Test
    void returnsNullWithoutPriceOrProfile() {
        assertThat(calculator.calculate(null, "USD", KOREA)).isNull();
        assertThat(calculator.calculate(BigDecimal.TEN, "USD", null)).isNull();
    }
}
