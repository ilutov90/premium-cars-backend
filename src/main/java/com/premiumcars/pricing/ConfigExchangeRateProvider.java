package com.premiumcars.pricing;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;

@Component
public class ConfigExchangeRateProvider implements ExchangeRateProvider {

    private final AppProperties props;

    public ConfigExchangeRateProvider(AppProperties props) {
        this.props = props;
    }

    @Override
    public BigDecimal eurPerUnit(String currency) {
        String code = currency == null ? "EUR" : currency.toUpperCase(Locale.ROOT);
        if ("EUR".equals(code)) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = props.pricing().exchangeRates().get(code);
        if (rate == null) {
            throw new BadRequestException("No exchange rate configured for currency " + code
                    + " (add it under app.pricing.exchange-rates)");
        }
        return rate;
    }
}
