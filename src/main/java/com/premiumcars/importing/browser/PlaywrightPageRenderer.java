package com.premiumcars.importing.browser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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

    /** Scrolls to the bottom in steps, so lazy galleries load their photos. */
    private static final String SCROLL_JS = """
            async () => {
              for (let y = 0; y < document.body.scrollHeight; y += 700) {
                window.scrollTo(0, y);
                await new Promise(r => setTimeout(r, 150));
              }
              window.scrollTo(0, 0);
            }""";

    /** All image URLs in the DOM: img src, srcset, data-src and CSS backgrounds. */
    private static final String DOM_IMAGES_JS = """
            () => {
              const out = [];
              document.querySelectorAll('img').forEach(i => {
                if (i.currentSrc || i.src) out.push(i.currentSrc || i.src);
                if (i.dataset && i.dataset.src) out.push(new URL(i.dataset.src, location.href).href);
              });
              document.querySelectorAll('[style*="background"]').forEach(e => {
                const m = /url\\(["']?([^"')]+)/.exec(e.getAttribute('style'));
                if (m) out.push(new URL(m[1], location.href).href);
              });
              return out;
            }""";

    private final AppProperties props;

    public PlaywrightPageRenderer(AppProperties props) {
        this.props = props;
    }

    @Override
    public boolean enabled() {
        return props.importer().browser().enabled();
    }

    @Override
    public synchronized BrowserImages loadImages(String pageUrl, List<List<String>> candidates,
                                                 List<BrowserCookie> cookies, Pattern pageImageFilter) {
        if (!enabled()) {
            return BrowserImages.NONE;
        }
        double timeoutMs = props.importer().browser().timeoutSeconds() * 1000.0;
        long started = System.currentTimeMillis();
        Map<Integer, byte[]> got = new HashMap<>();
        List<CapturedImage> pageImages = new ArrayList<>();
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

            // 1. The page and its own gallery.
            try {
                Response main = page.navigate(pageUrl, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(timeoutMs));
                log.info("Browser opened {}: HTTP {}, title '{}'", pageUrl, main == null ? "?" : main.status(),
                        page.title());
                page.evaluate(SCROLL_JS);
                page.waitForTimeout(2000);
                @SuppressWarnings("unchecked")
                List<String> domImages = (List<String>) page.evaluate(DOM_IMAGES_JS);
                List<String> shown = domImages.stream()
                        .filter(u -> pageImageFilter == null || pageImageFilter.matcher(u).find())
                        .distinct().toList();
                log.info("Page shows {} images ({} match the filter), e.g. {}", domImages.size(), shown.size(),
                        shown.stream().limit(5).toList());
            } catch (PlaywrightException e) {
                log.info("Could not open {} before loading photos: {}", pageUrl, e.getMessage());
            }
            if (pageImageFilter != null) {
                pageImages.addAll(capture(List.copyOf(responses), pageImageFilter));
            }
            logAnswers("Page image answers", List.copyOf(responses), pageImageFilter);

            // 2. The requested photos, as <img> elements on the page.
            int before = responses.size();
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
                long deadline = System.currentTimeMillis() + (long) Math.min(timeoutMs, 15_000);
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
            List<Response> requestedAnswers = List.copyOf(responses).subList(before, responses.size());
            logAnswers("Requested photo answers", requestedAnswers, null);
            browser.close();
        } catch (PlaywrightException e) {
            log.warn("Browser photo download failed for {}: {}", pageUrl, e.getMessage());
        }
        log.info("Browser photos for {}: {} of {} requested, {} loaded by the page, {} ms", pageUrl, got.size(),
                candidates.size(), pageImages.size(), System.currentTimeMillis() - started);
        return new BrowserImages(got, pageImages);
    }

    /** Images the page loaded (status 200, image type) whose URL matches the filter, without duplicates. */
    private static List<CapturedImage> capture(List<Response> responses, Pattern filter) {
        List<CapturedImage> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Response r : responses) {
            String type = r.headers().getOrDefault("content-type", "");
            if (r.status() != 200 || !type.contains("image") || !filter.matcher(r.url()).find()
                    || !seen.add(r.url())) {
                continue;
            }
            try {
                byte[] body = r.body();
                if (body != null && body.length > 0) {
                    out.add(new CapturedImage(r.url(), body));
                }
            } catch (PlaywrightException e) {
                log.debug("No body for page image {}: {}", r.url(), e.getMessage());
            }
        }
        return out;
    }

    /** One line: how the server answered image requests, e.g. {200 image/jpeg=12, 403 text/html=30} + a sample. */
    private static void logAnswers(String label, List<Response> responses, Pattern filter) {
        Map<String, Integer> counts = new TreeMap<>();
        List<String> sample = new ArrayList<>();
        for (Response r : responses) {
            String type = r.headers().getOrDefault("content-type", "");
            boolean imageLike = type.contains("image") || r.request().resourceType().equals("image");
            if (!imageLike || (filter != null && !filter.matcher(r.url()).find())) {
                continue;
            }
            counts.merge(r.status() + " " + type.split(";")[0], 1, Integer::sum);
            if (sample.size() < 3) {
                sample.add(r.status() + " " + r.url());
            }
        }
        log.info("{}: {} e.g. {}", label, counts, sample);
    }

    private static boolean allAnswered(List<Response> responses, Set<String> urls) {
        Set<String> answered = new HashSet<>();
        for (Response r : List.copyOf(responses)) {
            answered.add(r.url());
        }
        return answered.containsAll(urls);
    }

    @Override
    public synchronized Optional<RenderedPage> renderWhen(String url, Pattern ready) {
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
            Response main = page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(timeoutMs));
            long deadline = started + (long) timeoutMs;
            String html = "";
            int pages = 0;
            while (System.currentTimeMillis() < deadline) {
                try {
                    html = page.content();
                    if (ready.matcher(html).find()) {
                        browser.close();
                        log.info("Rendered {} in {} ms (HTTP {}, ready after {} checks)", url,
                                System.currentTimeMillis() - started, main == null ? "?" : main.status(), pages);
                        return Optional.of(new RenderedPage(html, List.of()));
                    }
                } catch (PlaywrightException e) {
                    // the page is navigating (a check page reloading itself); look again
                }
                pages++;
                page.waitForTimeout(500);
            }
            browser.close();
            log.warn("{} did not become ready in {} ms; last page {} characters, title '{}'", url,
                    System.currentTimeMillis() - started, html.length(), titleOf(html));
            return Optional.empty();
        } catch (PlaywrightException e) {
            log.warn("Browser rendering failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private static String titleOf(String html) {
        int a = html.indexOf("<title>");
        int b = a < 0 ? -1 : html.indexOf("</title>", a);
        return b < 0 ? "" : html.substring(a + 7, Math.min(b, a + 87));
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
