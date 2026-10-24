package com.premiumcars.importing.source.autowini;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;
import com.premiumcars.importing.browser.PageRenderer.BrowserCookie;

/**
 * Logged-in HTTP session with autowini.com. Port of AutowiniScraper.login() from autowini_poster_parallel.py.
 * <p>
 * Autowini signs in through Keycloak (OAuth2 SSO on a separate auth host): start the flow on autowini, read the
 * Keycloak form (or {@code kcContext.loginAction}), post the credentials, follow the redirects back. The session
 * lives in this bean's cookie jar (memory only); it logs in lazily on the first import and again when a page
 * shows the login form. Credentials come from configuration ({@code AUTOWINI_USER} / {@code AUTOWINI_PASS}),
 * never from the database.
 */
@Component
public class AutowiniClient {

    private static final Logger log = LoggerFactory.getLogger(AutowiniClient.class);

    static final String LOGIN_URL = "https://www.autowini.com/loginProcess";
    static final String LOGIN_PAGE = "https://www.autowini.com/joinfree/login";
    static final String OAUTH_INIT = "https://www.autowini.com/oauth2/authorization/keycloak";
    /** autowini's API behind its pages (the mobile listing page reads the price from it). */
    static final String V2_API = "https://v2api.autowini.com";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LOGIN_ACTION = Pattern.compile("\"loginAction\"\\s*:\\s*\"([^\"]+)\"");

    private final AppProperties props;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private boolean loggedIn;

    public AutowiniClient(AppProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .cookieHandler(cookies)
                // HTTP/1.1 like Python requests, which the site accepts; some CDN rules treat HTTP/2 clients differently
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(props.importer().timeoutSeconds()))
                .build();
    }

    /**
     * A listing page as fetched.
     *
     * @param mobile true when the desktop page was refused (HTTP 403) and this is the mobile site's page, which
     *               has no price
     */
    public record Listing(String html, boolean mobile) {
    }

    /** HTML of a listing page, logged in. Falls back to the mobile site when the desktop one answers 403. */
    public synchronized Listing fetchListing(String desktopUrl, String mobileUrl) {
        ensureLoggedIn();
        Page page = get(desktopUrl);
        log.info("autowini listing {}: HTTP {}", desktopUrl, page.status());
        boolean mobile = false;
        if (page.status() == 403 && mobileUrl != null) {
            page = get(mobileUrl);
            mobile = true;
            log.info("autowini mobile listing {}: HTTP {}", mobileUrl, page.status());
        }
        if (looksLikeLogin(page)) {
            log.info("autowini session expired, logging in again");
            loggedIn = false;
            ensureLoggedIn();
            page = get(desktopUrl);
            mobile = false;
        }
        if (page.status() != 200) {
            throw new BadRequestException("autowini returned HTTP " + page.status() + " for " + desktopUrl);
        }
        return new Listing(page.body(), mobile);
    }

    /**
     * A listing's price as autowini's pages show it, from the API the mobile page uses
     * ({@code GET /detail/v1/items/{id}} with the login's {@code token} cookie as Bearer token).
     *
     * @param bisPrice        the price shown to buyers ("FOB, Incheon"), USD
     * @param discountedPrice the price after an event discount, when autowini shows one; null otherwise
     */
    public record ApiPrices(BigDecimal bisPrice, BigDecimal discountedPrice) {
    }

    /**
     * The mobile page's script sends {@code Authorization: Bearer <cookie token>} only when that cookie exists (it
     * does not for this account: the API answers without it) and the selected country from {@code COUNTRY_INFO}.
     *
     * @return null when the API gives no price (refused, price hidden for the member, unexpected answer)
     */
    public synchronized ApiPrices itemPrices(String listingId) {
        ensureLoggedIn();
        HttpRequest.Builder request = base(V2_API + "/detail/v1/items/" + enc(listingId))
                .setHeader("Accept", "application/json")
                .header("Origin", "https://m.autowini.com")
                .header("Referer", "https://m.autowini.com/");
        String token = cookie("token");
        if (token != null) {
            request.header("Authorization", "Bearer " + (token.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? token.substring(7) : token));
        }
        String country = countrySubCode(cookie("COUNTRY_INFO"));
        if (country != null) {
            request.header("wini-code-select-country", country);
        }
        Page page = send(request.GET().build());
        if (page.status() != 200) {
            log.info("autowini API item {}: HTTP {} (token: {}, country: {}): {}", listingId, page.status(),
                    token != null, country, snippet(page.body()));
            return null;
        }
        ApiPrices prices = parsePrices(page.body());
        log.info("autowini API item {}: HTTP 200 (token: {}, country: {}), price {}", listingId, token != null,
                country, prices == null ? "not shown" : "USD " + prices.bisPrice().toPlainString());
        return prices;
    }

    /**
     * autowini's own USD to EUR rate: its pages show euro prices as the USD price times this rate (their script
     * {@code currency-ui.js} asks {@code POST /exchanges/current-rate?from=USD&to=EUR}). It is not the ECB rate:
     * it is what autowini charges in euro, so a car's euro price is taken from it.
     *
     * @return null when autowini does not answer with a usable rate
     */
    public synchronized BigDecimal usdToEurRate() {
        HttpRequest request = base(V2_API + "/exchanges/current-rate?from=USD&to=EUR")
                .setHeader("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", "https://www.autowini.com")
                .header("Referer", "https://www.autowini.com/")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        Page page;
        try {
            page = send(request);
        } catch (BadRequestException e) {
            log.info("autowini exchange rate: {}", e.getMessage());
            return null;
        }
        BigDecimal rate = page.status() == 200 ? parseRate(page.body()) : null;
        log.info("autowini exchange rate USD->EUR: HTTP {}, rate {}{}", page.status(), rate,
                rate == null ? " (answer: " + snippet(page.body()) + ")" : "");
        return rate;
    }

    /** {@code {"data": {"updateDate": "...", "rate": 0.9520, "ttl": 3600}}}; null unless a plausible rate. */
    static BigDecimal parseRate(String json) {
        try {
            JsonNode data = JSON.readTree(json).path("data");
            BigDecimal rate = positive(data.isNumber() || data.isTextual() ? data : data.path("rate"));
            // a USD -> EUR rate is around 0.8-1.0; anything far off is not this rate
            if (rate == null || rate.compareTo(new BigDecimal("0.5")) < 0 || rate.compareTo(new BigDecimal("1.5")) > 0) {
                return null;
            }
            return rate;
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code {"country_code":"BG","country_subCode":"C0280",...}} -> C0280 (the cookie may be URL-encoded) */
    static String countrySubCode(String countryInfo) {
        if (countryInfo == null) {
            return null;
        }
        Matcher m = Pattern.compile("\\\\?\"country_subCode\\\\?\"\\s*:\\s*\\\\?\"([A-Za-z0-9]+)").matcher(countryInfo);
        return m.find() ? m.group(1) : null;
    }

    private static String snippet(String body) {
        String s = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /** {@code {"data": {"prices": {"bisPrice": 16377, "isEventItem": false, "bisPriceWithDiscount": …}}}} */
    static ApiPrices parsePrices(String json) {
        try {
            JsonNode prices = JSON.readTree(json).path("data").path("prices");
            BigDecimal bis = positive(prices.path("bisPrice"));
            if (bis == null) {
                log.info("autowini API: no bisPrice (price fields: {}; answer: {})", fieldNames(prices), snippet(json));
                return null;
            }
            BigDecimal discounted = prices.path("isEventItem").asBoolean(false)
                    ? positive(prices.path("bisPriceWithDiscount"))
                    : null;
            return new ApiPrices(bis, discounted != null && discounted.compareTo(bis) < 0 ? discounted : null);
        } catch (IOException e) {
            log.info("autowini API: unreadable answer ({})", e.getMessage());
            return null;
        }
    }

    private static BigDecimal positive(JsonNode node) {
        if (node.isNumber()) {
            BigDecimal n = node.decimalValue();
            return n.signum() > 0 ? n : null;
        }
        if (node.isTextual()) {
            try {
                BigDecimal n = new BigDecimal(node.asText().replace(",", "").trim());
                return n.signum() > 0 ? n : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String cookie(String name) {
        for (HttpCookie c : cookies.getCookieStore().getCookies()) {
            if (c.getName().equals(name) && c.getValue() != null && !c.getValue().isBlank()
                    && (c.getDomain() == null || c.getDomain().toLowerCase(Locale.ROOT).contains("autowini"))) {
                return java.net.URLDecoder.decode(c.getValue(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Names only (no values), to see in the logs what the login left. */
    private List<String> cookieNames() {
        return cookies.getCookieStore().getCookies().stream().map(HttpCookie::getName).distinct().toList();
    }

    /**
     * The logged-in client, for downloading photos: imagebox.autowini.com wants the session cookies, like the
     * Python script, which downloads photos with the same requests.Session it logged in with.
     */
    public HttpClient sessionClient() {
        return http;
    }

    /** The session cookies, to carry the login into the headless browser. */
    public synchronized List<BrowserCookie> browserCookies() {
        List<BrowserCookie> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        var store = cookies.getCookieStore();
        for (URI uri : store.getURIs()) {
            for (HttpCookie c : store.get(uri)) {
                String domain = c.getDomain() != null ? c.getDomain() : uri.getHost();
                String path = c.getPath() != null ? c.getPath() : "/";
                if (domain != null && seen.add(c.getName() + "|" + domain + "|" + path)) {
                    out.add(new BrowserCookie(c.getName(), c.getValue(), domain, path));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- login

    private void ensureLoggedIn() {
        if (loggedIn) {
            return;
        }
        var cfg = props.importer().autowini();
        if (cfg.username() == null || cfg.username().isBlank() || cfg.password() == null || cfg.password().isBlank()) {
            throw new BadRequestException("autowini login is not configured: set AUTOWINI_USER and AUTOWINI_PASS "
                    + "(environment variables or the backend's .env file)");
        }
        login(cfg.username(), cfg.password());
        loggedIn = true;
    }

    void login(String username, String password) {
        Page kc = keycloakLoginPage();
        if (kc == null) {
            throw new BadRequestException("autowini: could not reach the Keycloak login page");
        }
        Document doc = Jsoup.parse(kc.body(), kc.url());
        Element form = doc.selectFirst("form#kc-form-login");
        if (form == null) {
            form = doc.select("form").stream().filter(f -> f.selectFirst("input[name=password]") != null)
                    .findFirst().orElse(null);
        }
        String action = form != null && !form.attr("action").isBlank() ? form.attr("action").replace("&amp;", "&") : null;
        if (action == null) {
            Matcher m = LOGIN_ACTION.matcher(kc.body());
            if (m.find()) {
                action = m.group(1).replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
            }
        }
        if (action == null) {
            throw new BadRequestException("autowini: login form not found on " + kc.url());
        }

        Map<String, String> payload = new LinkedHashMap<>();
        if (form != null) {
            for (Element input : form.select("input[name]")) {
                payload.put(input.attr("name"), input.attr("value"));
            }
        }
        payload.put("username", username);
        payload.put("password", password);
        payload.putIfAbsent("credentialId", "");

        URI actionUri = URI.create(kc.url()).resolve(action);
        Page result = post(actionUri.toString(), payload, Map.of(
                "Origin", actionUri.getScheme() + "://" + actionUri.getHost(),
                "Referer", kc.url()));

        String finalHost = URI.create(result.url()).getHost().toLowerCase(Locale.ROOT);
        boolean onAutowini = finalHost.contains("autowini.com");
        boolean onAuthHost = finalHost.contains("tradewini.com") || finalHost.contains("auth");
        if (onAutowini && !onAuthHost && !result.body().contains("kc-form-login")) {
            log.info("Logged in to autowini as {}", username);
            return;
        }
        String low = result.body().toLowerCase(Locale.ROOT);
        if (low.contains("captcha")) {
            throw new BadRequestException("autowini login needs a CAPTCHA; automatic login is not possible right now");
        }
        if (low.contains("invalid") || low.contains("incorrect") || low.contains("credential")) {
            throw new BadRequestException("autowini rejected the username or password (check AUTOWINI_USER / AUTOWINI_PASS)");
        }
        throw new BadRequestException("autowini login failed (ended on " + result.url() + ", HTTP " + result.status() + ")");
    }

    /** Starts the SSO flow from several entry points; returns the first Keycloak login page reached. */
    private Page keycloakLoginPage() {
        Map<String, String> empty = Map.of("username", "", "password", "", "i_sRememberId", "Y", "i_sRedirect", "");
        Page[] attempts = new Page[4];
        for (int i = 0; i < attempts.length; i++) {
            try {
                Page p = switch (i) {
                    case 0 -> post(LOGIN_URL, empty, Map.of());
                    case 1 -> get(OAUTH_INIT);
                    case 2 -> get(LOGIN_PAGE);
                    default -> get(LOGIN_URL);
                };
                if (p.status() == 200 && looksLikeKeycloak(p)) {
                    return p;
                }
            } catch (BadRequestException e) {
                log.debug("autowini login entry point {} failed: {}", i, e.getMessage());
            }
        }
        return null;
    }

    static boolean looksLikeKeycloak(Page p) {
        String host = URI.create(p.url()).getHost();
        String body = p.body();
        return p.url().contains("openid-connect/auth") || body.contains("login-actions")
                || body.contains("kc-form-login") || body.contains("kcContext")
                || body.contains("name=\"password\"") || body.contains("loginAction")
                || (host != null && host.contains("-auth."));
    }

    private static boolean looksLikeLogin(Page p) {
        return p.status() == 200 && (p.body().contains("kc-form-login") || p.url().contains("openid-connect/auth"));
    }

    // ---------------------------------------------------------------- HTTP

    record Page(int status, String url, String body) {
    }

    Page get(String url) {
        return send(base(url).GET().build());
    }

    private Page post(String url, Map<String, String> form, Map<String, String> headers) {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder b = base(url)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return send(b.build());
    }

    private HttpRequest.Builder base(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(props.importer().timeoutSeconds()))
                // The same headers as Python requests in the posting script, which autowini lets through.
                // With browser-like Accept headers and no Accept-Encoding it answered 403.
                .header("User-Agent", props.importer().userAgent())
                .header("Accept-Encoding", "gzip, deflate")
                .header("Accept", "*/*");
    }

    private Page send(HttpRequest request) {
        try {
            HttpResponse<byte[]> r = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String encoding = r.headers().firstValue("Content-Encoding").orElse("");
            return new Page(r.statusCode(), r.uri().toString(),
                    new String(decode(r.body(), encoding), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("autowini request interrupted", e);
        } catch (IOException e) {
            throw new BadRequestException("Could not reach autowini: " + e.getMessage(), e);
        }
    }

    /** Undoes gzip/deflate, which we accept like Python requests does. */
    static byte[] decode(byte[] body, String contentEncoding) throws IOException {
        String enc = contentEncoding.toLowerCase(Locale.ROOT).trim();
        if (body.length == 0 || enc.isEmpty() || enc.equals("identity")) {
            return body;
        }
        if (enc.contains("gzip")) {
            try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return in.readAllBytes();
            }
        }
        if (enc.contains("deflate")) {
            try (var in = new InflaterInputStream(new ByteArrayInputStream(body))) {
                return in.readAllBytes();
            } catch (ZipException e) {
                // some servers send raw deflate without the zlib header
                try (var in = new InflaterInputStream(new ByteArrayInputStream(body), new Inflater(true))) {
                    return in.readAllBytes();
                }
            }
        }
        return body;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
