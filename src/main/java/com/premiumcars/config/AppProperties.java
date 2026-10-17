package com.premiumcars.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All application settings live under the "app" prefix in application.yml.
 * Every value can be overridden with an environment variable, e.g. APP_ADMIN_PASSWORD.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Admin admin,
        Cors cors,
        Media media,
        Pricing pricing,
        Importer importer
) {

    public record Admin(String username, String password) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * @param dir     folder where downloaded car photos are stored
     * @param baseUrl public URL prefix the frontend uses to load them
     */
    public record Media(String dir, String baseUrl) {
    }

    /**
     * Simplified landed-cost model. All amounts in EUR, rates as fractions (0.10 = 10%).
     *
     * @param exchangeRates how many EUR one unit of the currency is worth (USD -> 0.92 means 1 USD = 0.92 EUR)
     */
    public record Pricing(
            BigDecimal defaultShippingEur,
            BigDecimal defaultMarginEur,
            BigDecimal fixedFeesEur,
            BigDecimal customsDutyRate,
            BigDecimal vatRate,
            Map<String, BigDecimal> exchangeRates
    ) {
    }

    /**
     * @param userAgent       browser user agent used when fetching source pages
     * @param timeoutSeconds  HTTP timeout for pages and photos
     * @param maxImages       maximum number of photos imported per car
     */
    public record Importer(String userAgent, int timeoutSeconds, int maxImages) {
    }
}
