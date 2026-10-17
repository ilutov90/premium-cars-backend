package com.premiumcars.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.premiumcars.config.AppProperties;

class PriceCalculatorTest {

    private final AppProperties props = new AppProperties(
            new AppProperties.Admin("admin", "x"),
            new AppProperties.Cors(List.of()),
            new AppProperties.Media("./data", "http://localhost/media/"),
            new AppProperties.Pricing(
                    new BigDecimal("2500"), new BigDecimal("1500"), new BigDecimal("600"),
                    new BigDecimal("0.10"), new BigDecimal("0.20"),
                    Map.of("USD", new BigDecimal("0.86"))),
            new AppProperties.Importer("test", 5, 10, new AppProperties.Browser(false, 10),
                    new AppProperties.Autowini(null, null)));

    private final PriceCalculator calculator = new PriceCalculator(props, new ConfigExchangeRateProvider(props));

    @Test
    void calculatesLandedCostWithDefaults() {
        PriceBreakdown b = calculator.calculate(new BigDecimal("17500"), "USD", null, null, null);

        // 17500 USD * 0.86 = 15050 EUR; CIF = 17550; duty 10% = 1755; VAT 20% of 19305 = 3861
        assertThat(b.carPriceEur()).isEqualByComparingTo("15050");
        assertThat(b.customsDutyEur()).isEqualByComparingTo("1755");
        assertThat(b.vatEur()).isEqualByComparingTo("3861");
        // 17550 + 1755 + 3861 + 600 + 1500
        assertThat(b.totalEur()).isEqualByComparingTo("25266");
    }

    @Test
    void appliesPerCarOverridesAndExtraDuty() {
        PriceBreakdown b = calculator.calculate(new BigDecimal("10000"), "USD",
                new BigDecimal("3000"), BigDecimal.ZERO, new BigDecimal("0.17"));

        // 8600 + 3000 = 11600; duty 27% = 3132; VAT 20% of 14732 = 2946.40
        assertThat(b.customsDutyRate()).isEqualByComparingTo("0.27");
        assertThat(b.customsDutyEur()).isEqualByComparingTo("3132");
        assertThat(b.vatEur()).isEqualByComparingTo("2946.40");
        assertThat(b.totalEur()).isEqualByComparingTo("18278"); // 11600+3132+2946.40+600+0 = 18278.40 -> 18278
    }

    @Test
    void returnsNullWithoutPrice() {
        assertThat(calculator.calculate(null, "USD", null, null, null)).isNull();
    }
}
