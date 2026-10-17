package com.premiumcars.importing.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private final AppProperties props;

    public PlaywrightPageRenderer(AppProperties props) {
        this.props = props;
    }

    @Override
    public boolean enabled() {
        return props.importer().browser().enabled();
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
