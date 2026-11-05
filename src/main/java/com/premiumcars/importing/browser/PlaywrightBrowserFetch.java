package com.premiumcars.importing.browser;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import com.premiumcars.config.AppProperties;

/**
 * {@link BrowserFetch} with Playwright: one headless Chromium on a thread of its own (Playwright objects may only be
 * used by the thread that made them), one page per site, kept open between requests and closed after
 * {@value #IDLE_MINUTES} idle minutes. Requests from any thread wait for their turn, one at a time. Separate from
 * {@link PlaywrightPageRenderer}, so a long run's searches never hold the imports' page loads.
 * <p>
 * Bot protections (autowini's Akamai) refuse a headless Chromium that calls itself another browser, so this one
 * keeps its own identity: headless, its user agent only loses "Headless"; on an admin's laptop (profile worker) it
 * is the installed Google Chrome in a window ({@code app.importer.browser.fetch-headless=false},
 * {@code fetch-channel=chrome}), the bundled Chromium when Chrome cannot start.
 */
@Component
public class PlaywrightBrowserFetch implements BrowserFetch, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserFetch.class);

    static final int IDLE_MINUTES = 10;

    /** fetch() from the page; with the site's cookies when its API allows them, else without. */
    private static final String FETCH_JS = """
            async a => {
              const init = { method: a.method, headers: a.headers, body: a.body || undefined };
              let r;
              try {
                r = await fetch(a.url, { ...init, credentials: 'include' });
              } catch (e) {
                r = await fetch(a.url, { ...init, credentials: 'omit' });
              }
              return { status: r.status, body: await r.text() };
            }""";

    /** A check page ("Security Verification", "Just a moment") reloads into the site when the browser passes it. */
    private static final String PASSED_CHECK_JS =
            "() => !/verif|just a moment|robot|captcha|access denied/i.test(document.title || '')";

    private record Site(BrowserContext context, Page page, long[] lastUse) {
    }

    private final AppProperties props;
    private final boolean headless;
    private final String channel;
    private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "browser-fetch");
        t.setDaemon(true);
        return t;
    });
    // used on the browser-fetch thread only
    private Playwright playwright;
    private Browser browser;
    private final Map<String, Site> sites = new HashMap<>();

    public PlaywrightBrowserFetch(AppProperties props,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${app.importer.browser.fetch-headless:true}") boolean headless,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${app.importer.browser.fetch-channel:}") String channel) {
        this.props = props;
        this.headless = headless;
        this.channel = channel == null ? "" : channel.trim();
        thread.scheduleWithFixedDelay(this::closeIdle, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public boolean enabled() {
        return props.importer().browser().enabled();
    }

    @Override
    public Answer fetch(String startUrl, String method, String url, String body, Map<String, String> headers) {
        if (!enabled()) {
            throw new IllegalStateException("The headless browser is off (app.importer.browser.enabled)");
        }
        long timeoutMs = props.importer().browser().timeoutSeconds() * 1000L;
        Future<Answer> answer = thread.submit(() -> fetchHere(startUrl, method, url, body, headers, timeoutMs));
        try {
            return answer.get(2 * timeoutMs + 30_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            answer.cancel(true);
            throw new IllegalStateException("Interrupted", e);
        } catch (TimeoutException e) {
            answer.cancel(true);
            throw new IllegalStateException("The browser did not answer in time for " + url);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException("The browser could not fetch " + url + ": " + cause.getMessage(), cause);
        }
    }

    private Answer fetchHere(String startUrl, String method, String url, String body, Map<String, String> headers,
                             long timeoutMs) {
        Site site = site(startUrl, timeoutMs);
        site.lastUse()[0] = System.currentTimeMillis();
        Map<String, Object> arg = new LinkedHashMap<>();
        arg.put("method", method);
        arg.put("url", url);
        arg.put("body", body == null ? "" : body);
        arg.put("headers", headers == null ? Map.of() : headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) site.page().evaluate(FETCH_JS, arg);
            return new Answer(((Number) r.get("status")).intValue(), String.valueOf(r.get("body")));
        } catch (PlaywrightException e) {
            close(startUrl); // the page broke (navigated away, crashed): opened again next time
            throw e;
        }
    }

    private Site site(String startUrl, long timeoutMs) {
        Site site = sites.get(startUrl);
        if (site != null) {
            return site;
        }
        if (browser == null || !browser.isConnected()) {
            stopBrowser();
            sites.clear();
            playwright = Playwright.create();
            browser = launch();
        }
        Browser.NewContextOptions options = new Browser.NewContextOptions().setLocale("en-US");
        String agent = ownAgent();
        if (agent != null) {
            options.setUserAgent(agent);
        }
        BrowserContext context = browser.newContext(options);
        Page page;
        Response main;
        try {
            page = context.newPage();
            page.setDefaultTimeout(timeoutMs);
            main = page.navigate(startUrl, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(timeoutMs));
        } catch (PlaywrightException e) {
            quietly(context::close);
            throw e;
        }
        try {
            page.waitForFunction(PASSED_CHECK_JS, null, new Page.WaitForFunctionOptions().setTimeout(timeoutMs));
        } catch (PlaywrightException e) {
            log.warn("Browser on {}: the page still looks like a check after {} s (title '{}')", startUrl,
                    timeoutMs / 1000, page.title());
        }
        page.waitForTimeout(1500); // the site's own scripts set their cookies
        log.info("Browser opened {} for its API: HTTP {}, title '{}'", startUrl, main == null ? "?" : main.status(),
                page.title());
        site = new Site(context, page, new long[] {System.currentTimeMillis()});
        sites.put(startUrl, site);
        return site;
    }

    /** The installed browser of {@code channel} (e.g. "chrome") when set and present, else the bundled Chromium. */
    private Browser launch() {
        if (!channel.isEmpty()) {
            try {
                Browser b = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(headless).setChannel(channel));
                log.info("Browser for the sites' APIs: {} {} ({})", channel, b.version(),
                        headless ? "headless" : "in a window");
                return b;
            } catch (PlaywrightException e) {
                log.warn("Browser channel '{}' did not start ({}); using the bundled Chromium", channel,
                        e.getMessage().lines().findFirst().orElse(""));
            }
        }
        Browser b = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
        log.info("Browser for the sites' APIs: Chromium {} ({})", b.version(), headless ? "headless" : "in a window");
        return b;
    }

    /**
     * Headless, the browser's own user agent without "Headless" (its version stays the one the browser shows in
     * every other way); in a window, null: nothing to change.
     */
    private String ownAgent() {
        if (!headless) {
            return null;
        }
        Page probe = browser.newPage();
        try {
            return String.valueOf(probe.evaluate("() => navigator.userAgent")).replace("HeadlessChrome", "Chrome");
        } finally {
            quietly(probe::close);
        }
    }

    private void closeIdle() {
        long limit = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(IDLE_MINUTES);
        for (Iterator<Map.Entry<String, Site>> it = sites.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Site> e = it.next();
            if (e.getValue().lastUse()[0] < limit) {
                quietly(() -> e.getValue().context().close());
                it.remove();
            }
        }
        if (sites.isEmpty()) {
            stopBrowser();
        }
    }

    private void close(String startUrl) {
        Site site = sites.remove(startUrl);
        if (site != null) {
            quietly(() -> site.context().close());
        }
    }

    private void stopBrowser() {
        if (browser != null) {
            Browser b = browser;
            quietly(b::close);
            browser = null;
        }
        if (playwright != null) {
            Playwright p = playwright;
            quietly(p::close);
            playwright = null;
        }
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // closing anyway
        }
    }

    @Override
    public void destroy() {
        thread.submit(() -> {
            sites.values().forEach(s -> quietly(() -> s.context().close()));
            sites.clear();
            stopBrowser();
        });
        thread.shutdown();
    }
}
