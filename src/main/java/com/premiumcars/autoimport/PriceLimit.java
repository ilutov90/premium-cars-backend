package com.premiumcars.autoimport;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.premiumcars.pricing.ExchangeRateProvider;

/**
 * The automatic import's highest FOB price ({@code auto_import_state.max_price_eur}, both sources): the sources'
 * search prices are FOB in USD and are compared in EUR at today's rate.
 */
@Component
public class PriceLimit {

    private final AutoImportSettingsRepository settings;
    private final ExchangeRateProvider rates;

    public PriceLimit(AutoImportSettingsRepository settings, ExchangeRateProvider rates) {
        this.settings = settings;
        this.rates = rates;
    }

    /** The limit now; null = none. */
    public Integer maxEur() {
        return settings.maxPriceEur();
    }

    /** The price (in the search's currency) is over the limit; an unknown price never is. */
    public boolean over(BigDecimal price, String currency, Integer maxEur) {
        if (maxEur == null || price == null || price.signum() <= 0) {
            return false;
        }
        return eur(price, currency).compareTo(BigDecimal.valueOf(maxEur)) > 0;
    }

    BigDecimal eur(BigDecimal price, String currency) {
        return "EUR".equalsIgnoreCase(currency) ? price : price.multiply(rates.eurPerUnit(currency));
    }
}
