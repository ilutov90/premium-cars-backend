package com.premiumcars.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriceCalculatorTest {

    private final PriceCalculator calculator = new PriceCalculator(
            currency -> "USD".equals(currency) ? new BigDecimal("0.86") : BigDecimal.ONE);

    /** autowini's duty and VAT table of October 31, 2026 (as migration 030 seeds it). */
    static final List<DutyBracket> KOREA_TABLE = koreaTable();

    private static List<DutyBracket> koreaTable() {
        List<DutyBracket> rows = new ArrayList<>();
        rows.add(new DutyBracket(new BigDecimal("10000"), new BigDecimal("2300")));
        int duty = 2300;
        for (int fob = 11000; fob <= 30000; fob += 1000) {
            duty += fob <= 15000 ? 60 : 80;
            rows.add(new DutyBracket(new BigDecimal(fob), new BigDecimal(duty)));
        }
        rows.add(new DutyBracket(null, new BigDecimal("4000")));
        return rows;
    }

    /** China (autohome, guazi): sea 2000, land 1600, commission 500, duty and VAT 20 %, markup 1000 / 1500. */
    private static final PricingProfile CHINA = profile(PriceProfile.AUTOHOME, "2000", "20", List.of());

    /** Korea (autowini): sea 1200, land 1600, commission 500, duty and VAT from the table, markup 1000 / 1500. */
    private static final PricingProfile KOREA = profile(PriceProfile.AUTOWINI, "1200", "0", KOREA_TABLE);

    private static PricingProfile profile(PriceProfile id, String sea, String dutyVat, List<DutyBracket> table) {
        return new PricingProfile(id, new BigDecimal(sea), new BigDecimal("1600"), new BigDecimal("500"),
                new BigDecimal(dutyVat), table, new BigDecimal("1000"), new BigDecimal("1500"),
                new BigDecimal("25000"), null);
    }

    @Test
    void theTableIsTheBusinessTable() {
        assertThat(KOREA_TABLE).hasSize(22);
        assertThat(KOREA_TABLE.get(5)).isEqualTo(new DutyBracket(new BigDecimal("15000"), new BigDecimal("2600")));
        assertThat(KOREA_TABLE.get(10)).isEqualTo(new DutyBracket(new BigDecimal("20000"), new BigDecimal("3000")));
        assertThat(KOREA_TABLE.get(20)).isEqualTo(new DutyBracket(new BigDecimal("30000"), new BigDecimal("3800")));
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
        assertThat(b.dutyVatTable()).isFalse();
        assertThat(b.dutyVatEur()).isEqualByComparingTo("4690");
        assertThat(b.profitEur()).isEqualByComparingTo("1000");
        assertThat(b.markupThresholdEur()).isEqualByComparingTo("25000");
        assertThat(b.stepEur()).isNull();
        assertThat(b.totalEur()).isEqualByComparingTo("31240");
    }

    @ParameterizedTest(name = "korea {0} EUR -> duty and VAT {1}, total {2}")
    @CsvSource({
            // fob, duty and VAT (the next row up), total = fob + 1200 + 1600 + 500 + duty + markup (1000 up to 25 000)
            "8000,     2300, 14600",
            "10000,    2300, 16600",
            "10001,    2360, 16661",
            "15000,    2600, 21900",
            "15700,    2680, 22680", // the 16 000 row
            "25000,    3400, 32700", // markup 1000 (25 000 inclusive)
            "30000,    3800, 38600", // markup 1500
            "30500,    4000, 39300", // the open row
            "50000,    4000, 58800",
    })
    void koreaTable(String fob, String dutyVat, String total) {
        PriceBreakdown b = calculator.calculate(new BigDecimal(fob), "EUR", KOREA);
        assertThat(b.dutyVatEur()).isEqualByComparingTo(dutyVat);
        assertThat(b.totalEur()).isEqualByComparingTo(total);
    }

    @Test
    void koreaShowsTheRowItUsed() {
        PriceBreakdown b = calculator.calculate(new BigDecimal("15700"), "EUR", KOREA);
        assertThat(b.dutyVatTable()).isTrue();
        assertThat(b.dutyVatFobUpToEur()).isEqualByComparingTo("16000");
        assertThat(b.dutyVatPct()).isNull();
        assertThat(calculator.calculate(new BigDecimal("40000"), "EUR", KOREA).dutyVatFobUpToEur()).isNull();
    }

    @Test
    void aTableWithoutAnOpenRowUsesItsLastRowAbove() {
        PricingProfile p = profile(PriceProfile.AUTOWINI, "1200", "0", KOREA_TABLE.subList(0, 21));
        assertThat(calculator.calculate(new BigDecimal("45000"), "EUR", p).dutyVatEur()).isEqualByComparingTo("3800");
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
    void anotherMarkupForOneCar() {
        // 14400 + 1200 + 1600 + 500 + 2600 (the 15 000 row) + 3500 (instead of the profile's 1000)
        PriceBreakdown b = calculator.calculate(new BigDecimal("14400"), "EUR", KOREA, new BigDecimal("3500"), null);

        assertThat(b.profitEur()).isEqualByComparingTo("3500");
        assertThat(b.profitOverridden()).isTrue();
        assertThat(b.markupThresholdEur()).isNull();
        assertThat(b.calculatedTotalEur()).isEqualByComparingTo("23800");
        assertThat(b.totalEur()).isEqualByComparingTo("23800");
        assertThat(b.fixedTotal()).isFalse();
    }

    @Test
    void finalPriceSetByHandWinsButTheCalculationStaysVisible() {
        PriceBreakdown b = calculator.calculate(new BigDecimal("14400"), "EUR", KOREA, null, new BigDecimal("21999.6"));

        assertThat(b.calculatedTotalEur()).isEqualByComparingTo("21300");
        assertThat(b.totalEur()).isEqualByComparingTo("22000"); // whole euros
        assertThat(b.fixedTotal()).isTrue();
        assertThat(b.dutyVatEur()).isEqualByComparingTo("2600");
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
        // 12345.12 + 1200 + 1600 + 500 + 2480 (the 13 000 row) + 1000 = 19125.12 -> 19125
        PriceBreakdown b = calculator.calculate(new BigDecimal("12345.12"), "EUR", KOREA);
        assertThat(b.dutyVatEur()).isEqualByComparingTo("2480");
        assertThat(b.totalEur()).isEqualByComparingTo("19125");
        assertThat(b.totalEur().scale()).isEqualTo(2);
    }

    @Test
    void returnsNullWithoutPriceOrProfile() {
        assertThat(calculator.calculate(null, "USD", KOREA)).isNull();
        assertThat(calculator.calculate(BigDecimal.TEN, "USD", null)).isNull();
    }
}
