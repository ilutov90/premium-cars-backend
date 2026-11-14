package com.premiumcars.autoimport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.premiumcars.autoimport.SourceSearch.SearchException;
import com.premiumcars.importing.browser.BrowserFetch;

/**
 * JSON requests to one site's search API, paced: at least {@code pauseMs} between two requests (gentle on the site,
 * also when a preview and a run overlap). Retries twice on network errors, 429 and 5xx. A site that refuses the
 * server (HTTP 403, or a page such as a bot check instead of data) is asked again from the headless browser, on the
 * site's own page ({@link BrowserFetch}), and then through the browser for {@link #BROWSER_FOR} (autowini's search
 * API answers its visitors' browsers only since Nov 2026).
 */
final class SearchHttp {

    private static final Logger log = LoggerFactory.getLogger(SearchHttp.class);

    private static final int ATTEMPTS = 3;
    static final Duration BROWSER_FOR = Duration.ofHours(6);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper json;
    private final long pauseMs;
    private final Map<String, String> headers;
    private final BrowserFetch browser;
    private final String startUrl;
    private volatile long browserUntil;
    private volatile Instant lastRefused;
    private long last;

    SearchHttp(ObjectMapper json, long pauseMs, Map<String, String> headers) {
        this(json, pauseMs, headers, null, null);
    }

    /**
     * @param browser  asks the site from a headless browser when it refuses the server; null = never
     * @param startUrl the site's page the browser asks from (its own pages call the API)
     */
    SearchHttp(ObjectMapper json, long pauseMs, Map<String, String> headers, BrowserFetch browser, String startUrl) {
        this.json = json;
        this.pauseMs = pauseMs;
        this.headers = Map.copyOf(headers);
        this.browser = browser;
        this.startUrl = startUrl;
    }

    JsonNode get(URI uri) {
        return send("GET", uri, null);
    }

    JsonNode post(URI uri, Object body) {
        try {
            return send("POST", uri, json.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The search goes through the browser now (after a refusal). */
    boolean viaBrowser() {
        return browserUntil > System.currentTimeMillis();
    }

    /** When a refusal last left {@link #send} (the browser refused too, or there is none); null = never. */
    Instant lastRefused() {
        return lastRefused;
    }

    private JsonNode send(String method, URI uri, String body) {
        try {
            return sendOnce(method, uri, body);
        } catch (Refused e) {
            lastRefused = Instant.now();
            throw e;
        }
    }

    private JsonNode sendOnce(String method, URI uri, String body) {
        if (viaBrowser()) {
            return sendFromBrowser(method, uri, body);
        }
        try {
            return sendDirect(method, uri, body);
        } catch (Refused e) {
            if (browser == null || !browser.enabled()) {
                throw e;
            }
            log.info("{} refused the server ({}); asking from the browser for the next {} hours", uri.getHost(),
                    e.getMessage().length() > 120 ? e.getMessage().substring(0, 120) : e.getMessage(),
                    BROWSER_FOR.toHours());
            browserUntil = System.currentTimeMillis() + BROWSER_FOR.toMillis();
            return sendFromBrowser(method, uri, body);
        }
    }

    private JsonNode sendDirect(String method, URI uri, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        headers.forEach(builder::header);
        HttpRequest request = builder.header("Accept", "application/json").timeout(Duration.ofSeconds(30)).build();
        String problem = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            pace();
            try {
                HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = r.statusCode();
                if (status == 429 || status >= 500) {
                    problem = "HTTP " + status;
                    sleep(2000L * attempt);
                    continue;
                }
                return parse(uri.getHost(), status, r.body());
            } catch (IOException e) {
                problem = e.toString();
                sleep(2000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SearchException("Interrupted", e);
            }
        }
        throw new SearchException(uri.getHost() + " did not answer: " + problem);
    }

    private JsonNode sendFromBrowser(String method, URI uri, String body) {
        Map<String, String> h = "POST".equals(method)
                ? Map.of("Accept", "application/json", "Content-Type", "application/json")
                : Map.of("Accept", "application/json");
        String problem = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            pace();
            try {
                BrowserFetch.Answer a = browser.fetch(startUrl, method, uri.toString(), body, h);
                if (a.status() == 429 || a.status() >= 500) {
                    problem = "HTTP " + a.status() + " (browser)";
                    sleep(2000L * attempt);
                    continue;
                }
                return parse(uri.getHost() + " (browser)", a.status(), a.body());
            } catch (IllegalStateException e) {
                problem = e.getMessage();
                sleep(2000L * attempt);
            }
        }
        throw new SearchException(uri.getHost() + " did not answer the browser: " + problem);
    }

    private JsonNode parse(String host, int status, String text) {
        String body = text == null ? "" : text;
        try {
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject()) {
                throw status == 403 ? new Refused(host + " answered HTTP 403 without data")
                        : new SearchException(host + " answered HTTP " + status + " without data");
            }
            return node;
        } catch (JsonProcessingException e) {
            String start = body.strip();
            throw new Refused(host + " answered HTTP " + status + " with a page instead of data (a bot check?): "
                    + refusalDetail(start));
        }
    }

    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** What to say of a refusing page: its title in quotes, else its first 80 characters without tags. */
    static String refusalDetail(String page) {
        String html = page == null ? "" : page;
        Matcher m = TITLE.matcher(html);
        if (m.find()) {
            String title = m.group(1).replaceAll("\\s+", " ").strip();
            if (!title.isEmpty()) {
                return "\"" + (title.length() > 80 ? title.substring(0, 80) : title) + "\"";
            }
        }
        String text = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]*>", " ")
                .replaceAll("\\s+", " ").strip();
        return text.length() > 80 ? text.substring(0, 80) : text;
    }

    /** The site refused us: a bot check or HTTP 403 instead of data. */
    static final class Refused extends SearchException {

        Refused(String message) {
            super(message);
        }
    }
    private synchronized void pace() {
        long wait = last + pauseMs - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        last = System.currentTimeMillis();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchException("Interrupted", e);
        }
    }
}
