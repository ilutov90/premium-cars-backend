package com.premiumcars.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.premiumcars.config.AppProperties;

/**
 * Simplified import cost model for a car brought into Bulgaria from outside the EU:
 *
 * <pre>
 *   customs value (CIF) = car price in EUR + shipping
 *   duty                = CIF * (standard duty rate + per-car extra duty)
 *   VAT                 = (CIF + duty) * VAT rate
 *   total               = CIF + duty + VAT + fixed fees + margin
 * </pre>
 *
 * The rates are configuration, not law: check them with a customs broker and an accountant
 * (for example the extra EU duties on Chinese electric cars differ per manufacturer).
 */
@Component
public class PriceCalculator {

    private static final int SCALE = 2;

    private final AppProperties props;
    private final ExchangeRateProvider rates;

    public PriceCalculator(AppProperties props, ExchangeRateProvider rates) {
        this.props = props;
        this.rates = rates;
    }

    /**
     * @param shippingOverride  null = default from configuration
     * @param marginOverride    null = default from configuration
     * @param extraDutyRate     null = 0
     * @return null when the source price is unknown
     */
    public PriceBreakdown calculate(BigDecimal sourcePrice, String sourceCurrency,
                                    BigDecimal shippingOverride, BigDecimal marginOverride,
                                    BigDecimal extraDutyRate) {
        if (sourcePrice == null) {
            return null;
        }
        var cfg = props.pricing();
        String currency = sourceCurrency == null ? "USD" : sourceCurrency;

        BigDecimal carPriceEur = round(sourcePrice.multiply(rates.eurPerUnit(currency)));
        BigDecimal shipping = shippingOverride != null ? shippingOverride : cfg.defaultShippingEur();
        BigDecimal margin = marginOverride != null ? marginOverride : cfg.defaultMarginEur();
        BigDecimal dutyRate = cfg.customsDutyRate().add(extraDutyRate != null ? extraDutyRate : BigDecimal.ZERO);

        BigDecimal cif = carPriceEur.add(shipping);
        BigDecimal duty = round(cif.multiply(dutyRate));
        BigDecimal vat = round(cif.add(duty).multiply(cfg.vatRate()));
        BigDecimal total = cif.add(duty).add(vat).add(cfg.fixedFeesEur()).add(margin)
                .setScale(0, RoundingMode.HALF_UP)
                .setScale(SCALE, RoundingMode.UNNECESSARY);

        return new PriceBreakdown(sourcePrice, currency, carPriceEur, round(shipping), dutyRate, duty,
                cfg.vatRate(), vat, round(cfg.fixedFeesEur()), round(margin), total);
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
