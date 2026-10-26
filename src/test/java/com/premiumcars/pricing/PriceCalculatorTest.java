package com.premiumcars.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriceCalculatorTest {

    private final PriceCalculator calculator = new PriceCalculator(
            currency -> "USD".equals(currency) ? new BigDecimal("0.86") : BigDecimal.ONE);

    /** China (autohome, guazi): sea 2000, land 1600, commission 500, profit 2000, duty and VAT 20 %. */
    private static final PricingProfile CHINA = new PricingProfile(PriceProfile.AUTOHOME, new BigDecimal("2000"),
            new BigDecimal("1600"), new BigDecimal("500"), new BigDecimal("2000"), new BigDecimal("20"), null);

    /** Korea (autowini): sea 1200, land 1600, commission 500, profit 2000, duty and VAT 17 %. */
    private static final PricingProfile KOREA = new PricingProfile(PriceProfile.AUTOWINI, new BigDecimal("1200"),
            new BigDecimal("1600"), new BigDecimal("500"), new BigDecimal("2000"), new BigDecimal("17"), null);

    @Test
    void chinaTheBusinessExample() {
        // 21450 + 2000 + 1600 + 500 + (21450 + 2000) * 0.20 = 4690, + 2000 profit
        PriceBreakdown b = calculator.calculate(new BigDecimal("21450"), "EUR", CHINA);

        assertThat(b.profile()).isEqualTo(PriceProfile.AUTOHOME);
        assertThat(b.carPriceEur()).isEqualByComparingTo("21450");
        assertThat(b.seaFreightEur()).isEqualByComparingTo("2000");
        assertThat(b.landTransportEur()).isEqualByComparingTo("1600");
        assertThat(b.commissionEur()).isEqualByComparingTo("500");
        assertThat(b.dutyVatPct()).isEqualByComparingTo("20");
        assertThat(b.dutyVatPct().toString()).isEqualTo("20");
        assertThat(b.dutyVatEur()).isEqualByComparingTo("4690");
        assertThat(b.profitEur()).isEqualByComparingTo("2000");
        assertThat(b.baseCostsEur()).isNull();
        assertThat(b.steps()).isNull();
        assertThat(b.totalEur()).isEqualByComparingTo("32240");
    }

    @Test
    void koreaTheBusinessExample() {
        // 14400 + 1200 + 1600 + 500 + (14400 + 1200) * 0.17 = 2652, + 2000 profit
        PriceBreakdown b = calculator.calculate(new BigDecimal("14400"), "EUR", KOREA);

        assertThat(b.dutyVatEur()).isEqualByComparingTo("2652");
        assertThat(b.totalEur()).isEqualByComparingTo("22352");
    }

    @ParameterizedTest(name = "china {0} EUR -> {1}")
    @CsvSource({
            // fob, total = fob + 2000 + 1600 + 500 + 0.20 * (fob + 2000) + 2000
            "8000,   16100",
            "10000,  18500",
            "25000,  36500",
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
        assertThat(b.totalEur()).isEqualByComparingTo("37460");
    }

    @Test
    void anotherProfitForOneCar() {
        // 14400 + 1200 + 1600 + 500 + 2652 + 3500 (instead of the profile's 2000)
        PriceBreakdown b = calculator.calculate(new BigDecimal("14400"), "EUR", KOREA, new BigDecimal("3500"), null);

        assertThat(b.profitEur()).isEqualByComparingTo("3500");
        assertThat(b.profitOverridden()).isTrue();
        assertThat(b.calculatedTotalEur()).isEqualByComparingTo("23852");
        assertThat(b.totalEur()).isEqualByComparingTo("23852");
        assertThat(b.fixedTotal()).isFalse();
    }

    @Test
    void finalPriceSetByHandWinsButTheCalculationStaysVisible() {
        PriceBreakdown b = calculator.calculate(new BigDecimal("14400"), "EUR", KOREA, null, new BigDecimal("21999.6"));

        assertThat(b.calculatedTotalEur()).isEqualByComparingTo("22352");
        assertThat(b.totalEur()).isEqualByComparingTo("22000"); // whole euros
        assertThat(b.fixedTotal()).isTrue();
        assertThat(b.dutyVatEur()).isEqualByComparingTo("2652");
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
        // 12345.67 + 1200 + 1600 + 500 + 0.17 * 13545.67 (2302.76) + 2000 = 19948.43 -> 19948
        PriceBreakdown b = calculator.calculate(new BigDecimal("12345.67"), "EUR", KOREA);
        assertThat(b.dutyVatEur()).isEqualByComparingTo("2302.76");
        assertThat(b.totalEur()).isEqualByComparingTo("19948");
        assertThat(b.totalEur().scale()).isEqualTo(2);
    }

    @Test
    void returnsNullWithoutPriceOrProfile() {
        assertThat(calculator.calculate(null, "USD", KOREA)).isNull();
        assertThat(calculator.calculate(BigDecimal.TEN, "USD", null)).isNull();
    }
}
