package com.premiumcars.importing.browser;

import java.util.Map;

/**
 * Requests made by a real (headless) browser from a site's own page, with fetch(): for site APIs that refuse plain
 * HTTP clients but answer the site's visitors (autowini's search API since Nov 2026). The browser opens the site's
 * page once and keeps it for later requests.
 */
public interface BrowserFetch {

    /** @param status the HTTP status; body the answer as text */
    record Answer(int status, String body) {
    }

    /** The headless browser is configured (app.importer.browser.enabled). */
    boolean enabled();

    /**
     * @param startUrl the site's page the request is made from (opened once, then reused)
     * @param method   GET or POST
     * @param url      the request
     * @param body     the request body (POST), or null
     * @param headers  extra request headers (Accept, Content-Type)
     * @throws IllegalStateException when the browser could not make the request
     */
    Answer fetch(String startUrl, String method, String url, String body, Map<String, String> headers);
}
