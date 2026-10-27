package com.premiumcars.importing.source.guazi;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.importing.PageFetcher;
import com.premiumcars.importing.SourceAdapter;
import com.premiumcars.importing.browser.PageRenderer;
import com.premiumcars.importing.browser.PageRenderer.RenderedPage;
import com.premiumcars.importing.source.guazi.GuaziPageParser.ParsedPage;

/**
 * en.guazi.com (Guazi's export site, cars in China). No login, no browser: the page's HTML holds the whole listing
 * (see {@link GuaziPageParser}). Prices are FOB in USD; the car is priced with the autohome formula.
 * <p>
 * guazi answers plain requests from our server with a check page (a script that reloads into the listing). With
 * guazi's permission (October 2026) the page is then opened in the headless browser ({@link PageRenderer}), which
 * runs that script like a visitor's browser. Browser loads are kept at least {@link #MIN_INTERVAL_MS} apart and run
 * one at a time. A page saved in the admin's own browser can still be imported ({@link #importFromHtml}).
 */
@Component
@Order(12)
public class GuaziSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(GuaziSourceAdapter.class);

    /** Pause between two browser loads of guazi pages: gentle on their site, also for bulk imports later. */
    static final long MIN_INTERVAL_MS = 1_000;
    /** The listing is there once the page's Next.js data holds rawData. */
    private static final Pattern LISTING_READY = Pattern.compile("rawData");

    private final PageFetcher fetcher;
    private final PageRenderer renderer;
    private long lastBrowserLoad;

    public GuaziSourceAdapter(PageFetcher fetcher, PageRenderer renderer) {
        this.fetcher = fetcher;
        this.renderer = renderer;
    }

    @Override
    public CarSource source() {
        return CarSource.GUAZI;
    }

    @Override
    public boolean supports(URI url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        return host.equals("guazi.com") || host.endsWith(".guazi.com");
    }

    /** The English page without query: the same listing in another language or with tracking is a duplicate. */
    @Override
    public URI canonicalUrl(URI url) {
        return GuaziPageParser.englishUrl(url);
    }

    @Override
    public String referer(URI listingUrl) {
        return "https://en.guazi.com/";
    }

    /** The photos load in any browser: the site shows them from guazi's CDN, nothing is stored. */
    @Override
    public boolean linksPhotos() {
        return true;
    }

    @Override
    public List<String> imageCandidates(String imageUrl) {
        return GuaziPageParser.imageCandidates(imageUrl);
    }

    @Override
    public ImportedCar importFrom(URI url) {
        URI page = canonicalUrl(url);
        String id = GuaziPageParser.productId(page.toString());
        if (id == null) {
            throw new BadRequestException("Not a guazi car page (expected https://en.guazi.com/products/...html): "
                    + url);
        }
        String html = fetcher.fetchHtml(page);
        if (GuaziPageParser.hasListing(html) || !renderer.enabled()) {
            return fromPage(html, id, false);
        }
        log.info("guazi {}: plain download got no listing ({} characters), opening it in the browser", id,
                html.length());
        Optional<RenderedPage> rendered = browserLoad(page);
        if (rendered.isEmpty()) {
            throw new BadRequestException("guazi did not show the listing, not even in the browser: " + page
                    + " (see the server log; meanwhile import the page saved in your browser)");
        }
        return fromPage(rendered.get().html(), id, false);
    }

    /** One browser load at a time, at least {@link #MIN_INTERVAL_MS} after the previous one. */
    private synchronized Optional<RenderedPage> browserLoad(URI page) {
        long wait = lastBrowserLoad + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        try {
            return renderer.renderWhen(page.toString(), LISTING_READY);
        } finally {
            lastBrowserLoad = System.currentTimeMillis();
        }
    }

    @Override
    public ImportedCar importFromHtml(URI url, String html) {
        String id = GuaziPageParser.productId(canonicalUrl(url).toString());
        if (id == null) {
            throw new BadRequestException("Not a guazi car page (expected https://en.guazi.com/products/...html): "
                    + url);
        }
        return fromPage(html, id, true);
    }

    private ImportedCar fromPage(String html, String id, boolean saved) {
        ParsedPage parsed;
        try {
            parsed = GuaziPageParser.parse(html, id);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(saved
                    ? "The saved file is not this guazi listing: " + e.getMessage()
                    : "Could not read the guazi page (browser rendering is off?): " + e.getMessage(), e);
        }
        if (saved && !id.equalsIgnoreCase(parsed.productId())) {
            throw new BadRequestException("The saved file is listing " + parsed.productId() + ", not " + id);
        }
        CarSpecs specs = GuaziPageParser.toSpecs(parsed);

        List<String> warnings = new ArrayList<>();
        if (parsed.priceUsd() == null) {
            warnings.add("Price not found: enter it by hand.");
        }
        if (specs.fuelType() != null && specs.fuelType().hasTractionBattery()) {
            if (specs.batteryKwh() == null) {
                warnings.add("Battery capacity not found.");
            }
            if (specs.electricRangeKm() == null) {
                warnings.add("Electric range not found.");
            }
        }
        if (specs.euroStandard() == null && specs.fuelType() != null
                && specs.fuelType() != FuelType.ELECTRIC) {
            warnings.add("guazi does not give the emission class (Euro): set it for mobile.bg.");
        }
        if (parsed.inspection() != null) {
            warnings.add(parsed.inspection());
        }
        log.info("guazi {}: {} | {} USD | {} photos", parsed.productId(), specs.title(), parsed.priceUsd(),
                parsed.images().size());
        return new ImportedCar(parsed.productId(), parsed.fields().get("location"), specs, parsed.priceUsd(),
                parsed.priceUsd() == null ? null : "USD", parsed.images(), warnings);
    }
}
