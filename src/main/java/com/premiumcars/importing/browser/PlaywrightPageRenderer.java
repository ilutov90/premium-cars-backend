package com.premiumcars.importing.browser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.WaitUntilState;
import com.premiumcars.config.AppProperties;

/**
 * Headless Chromium through Playwright for Java. The browser is downloaded automatically on first use
 * (about 150 MB into the user's ms-playwright cache) and started per call, so nothing runs while idle.
 * Calls are serialized: one browser at a time.
 */
@Component
public class PlaywrightPageRenderer implements PageRenderer {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightPageRenderer.class);

    /** Adds one tiny <img> per URL, so the browser requests each photo exactly as it would for the page. */
    private static final String ADD_IMAGES_JS = """
            urls => urls.forEach(u => {
              const img = document.createElement('img');
              img.loading = 'eager';
              img.src = u;
              img.style.cssText = 'width:1px;height:1px';
              (document.body || document.documentElement).appendChild(img);
            })""";

    private final AppProperties props;

    public PlaywrightPageRenderer(AppProperties props) {
        this.props = props;
    }

    @Override
    public boolean enabled() {
        return props.importer().browser().enabled();
    }

    @Override
    public synchronized Map<Integer, byte[]> loadImages(String pageUrl, List<List<String>> candidates,
                                                        List<BrowserCookie> cookies) {
        if (!enabled() || candidates.isEmpty()) {
            return Map.of();
        }
        double timeoutMs = props.importer().browser().timeoutSeconds() * 1000.0;
        long started = System.currentTimeMillis();
        Map<Integer, byte[]> got = new HashMap<>();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(props.importer().userAgent())
                    .setLocale("en-US"));
            if (!cookies.isEmpty()) {
                context.addCookies(cookies.stream()
                        .map(c -> new Cookie(c.name(), c.value()).setDomain(c.domain()).setPath(c.path()))
                        .toList());
            }
            Page page = context.newPage();
            page.setDefaultTimeout(timeoutMs);
            List<Response> responses = new ArrayList<>();
            page.onResponse(responses::add);

            try {
                page.navigate(pageUrl, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(timeoutMs));
                page.waitForTimeout(1000);
            } catch (PlaywrightException e) {
                log.debug("Could not open {} before loading photos: {}", pageUrl, e.getMessage());
            }

            int rounds = candidates.stream().mapToInt(List::size).max().orElse(0);
            for (int round = 0; round < rounds; round++) {
                Map<String, Integer> wanted = new LinkedHashMap<>();
                for (int i = 0; i < candidates.size(); i++) {
                    if (!got.containsKey(i) && round < candidates.get(i).size()) {
                        wanted.put(candidates.get(i).get(round), i);
                    }
                }
                if (wanted.isEmpty()) {
                    break;
                }
                page.evaluate(ADD_IMAGES_JS, new ArrayList<>(wanted.keySet()));
                long deadline = System.currentTimeMillis() + (long) Math.min(timeoutMs, 30_000);
                while (System.currentTimeMillis() < deadline && !allAnswered(responses, wanted.keySet())) {
                    page.waitForTimeout(250);
                }
                // Snapshot: reading a body lets Playwright deliver more events into the list.
                for (Response r : List.copyOf(responses)) {
                    Integer index = wanted.get(r.url());
                    if (index == null || got.containsKey(index) || r.status() != 200) {
                        continue;
                    }
                    try {
                        byte[] body = r.body();
                        if (body != null && body.length > 0) {
                            got.put(index, body);
                        }
                    } catch (PlaywrightException e) {
                        log.debug("No body for photo {}: {}", r.url(), e.getMessage());
                    }
                }
            }
            browser.close();
        } catch (PlaywrightException e) {
            log.warn("Browser photo download failed for {}: {}", pageUrl, e.getMessage());
        }
        log.info("Loaded {} of {} photos in the browser from {} in {} ms", got.size(), candidates.size(), pageUrl,
                System.currentTimeMillis() - started);
        return got;
    }

    private static boolean allAnswered(List<Response> responses, Set<String> urls) {
        Set<String> answered = new HashSet<>();
        for (Response r : List.copyOf(responses)) {
            answered.add(r.url());
        }
        return answered.containsAll(urls);
    }

    @Override
    public synchronized Optional<RenderedPage> render(String url, List<Pattern> clickTexts, Pattern responseHint) {
        if (!enabled()) {
            return Optional.empty();
        }
        double timeoutMs = props.importer().browser().timeoutSeconds() * 1000.0;
        long started = System.currentTimeMillis();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(props.importer().userAgent())
                    .setLocale("en-US"));
            Page page = context.newPage();
            page.setDefaultTimeout(timeoutMs);

            // Keep the responses; read their bodies after loading (not inside the event handler).
            List<Response> responses = new ArrayList<>();
            page.onResponse(responses::add);

            page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE).setTimeout(timeoutMs));
            page.waitForTimeout(1500);

            List<String> texts = new ArrayList<>();
            for (Pattern caption : clickTexts) {
                Locator matches = page.getByText(caption);
                int count = Math.min(matches.count(), 2);
                for (int i = 0; i < count; i++) {
                    try {
                        matches.nth(i).click(new Locator.ClickOptions().setTimeout(3000));
                        page.waitForTimeout(1000);
                        texts.add(page.innerText("body"));
                    } catch (PlaywrightException e) {
                        log.debug("Could not click '{}' on {}: {}", caption, url, e.getMessage());
                    }
                }
            }
            String html = page.content();

            if (responseHint != null) {
                for (Response r : responses) {
                    try {
                        String type = r.headers().getOrDefault("content-type", "");
                        if (r.status() == 200 && (type.contains("json") || type.contains("x-component"))) {
                            String body = r.text();
                            if (responseHint.matcher(body).find()) {
                                texts.add(body);
                            }
                        }
                    } catch (PlaywrightException e) {
                        log.debug("Skipping response body of {}: {}", r.url(), e.getMessage());
                    }
                }
            }
            browser.close();
            log.info("Rendered {} in {} ms ({} extra texts)", url, System.currentTimeMillis() - started, texts.size());
            return Optional.of(new RenderedPage(html, texts));
        } catch (PlaywrightException e) {
            log.warn("Browser rendering failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }
}
