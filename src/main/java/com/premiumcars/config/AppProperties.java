package com.premiumcars.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All application settings live under the "app" prefix in application.yml.
 * Every value can be overridden with an environment variable, e.g. APP_SECURE_COOKIES; secrets come from Vault.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Admin admin,
        Cors cors,
        Media media,
        Pricing pricing,
        Importer importer,
        Social social
) {

    /**
     * @param initialPassword creates the "admin" user when the admin_user table is empty (from Vault:
     *                        ADMIN_INITIAL_PASSWORD); ignored afterwards
     * @param secureCookies   mark the CSRF cookie Secure (HTTPS only); the session cookie follows
     *                        server.servlet.session.cookie.secure
     */
    public record Admin(String initialPassword, boolean secureCookies) {
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
     * Price formulas (costs, profit) live in the database and are edited in the admin panel; only the
     * exchange rates are configuration.
     *
     * @param exchangeRates how many EUR one unit of the currency is worth (USD -> 0.92 means 1 USD = 0.92 EUR)
     */
    public record Pricing(Map<String, BigDecimal> exchangeRates) {
    }

    /**
     * @param userAgent       browser user agent used when fetching source pages
     * @param timeoutSeconds  HTTP timeout for pages and photos
     * @param maxImages       maximum number of photos imported per car
     * @param browser         headless browser for pages that load data with JavaScript
     * @param autowini        autowini.com account (from AUTOWINI_USER / AUTOWINI_PASS)
     */
    public record Importer(String userAgent, int timeoutSeconds, int maxImages, Browser browser, Autowini autowini) {
    }

    /** @param enabled false skips rendering: imports are faster but may miss photos and EV data */
    public record Browser(boolean enabled, int timeoutSeconds) {
    }

    public record Autowini(String username, String password) {
    }

    /**
     * Facebook page / Instagram account to publish cars to, through the Meta Graph API. Same variable names as the
     * posting script's .env (FB_PAGE_ID, FB_ACCESS_TOKEN, IG_USER_ID, IMGBB_API_KEY); empty = that network is off.
     *
     * @param graphVersion     Graph API version, e.g. v23.0
     * @param accessToken      a long-lived Page access token (also used for the Instagram account linked to the page)
     * @param imgbbApiKey      Instagram needs public photo URLs; photos are uploaded to imgbb for that
     * @param maxPhotos        photos per post (Instagram carousels take at most 10)
     * @param viberChannelToken the Viber channel's token (channel info -> Developer tools), VIBER_CHANNEL_TOKEN
     * @param viberMaxPhotos   photos per Viber post (each photo is its own message in the channel)
     */
    public record Social(String graphVersion, String facebookPageId, String accessToken, String instagramUserId,
                         String imgbbApiKey, int maxPhotos, String viberChannelToken, int viberMaxPhotos) {

        public boolean facebookConfigured() {
            return notBlank(facebookPageId) && notBlank(accessToken);
        }

        public boolean instagramConfigured() {
            return notBlank(instagramUserId) && notBlank(accessToken) && notBlank(imgbbApiKey);
        }

        /** Viber fetches photos from public URLs too, so it needs imgbb like Instagram. */
        public boolean viberConfigured() {
            return notBlank(viberChannelToken) && notBlank(imgbbApiKey);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}
