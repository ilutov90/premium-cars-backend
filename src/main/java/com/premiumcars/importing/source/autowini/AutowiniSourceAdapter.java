package com.premiumcars.importing.source.autowini;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.importing.SourceAdapter;
import com.premiumcars.importing.browser.PageRenderer;
import com.premiumcars.importing.source.autowini.AutowiniPageParser.ParsedPage;
import com.premiumcars.media.ImageDownloader;
import com.premiumcars.media.ImageDownloader.DownloadedImage;

/**
 * autowini.com (South Korea). Pages are read with a logged-in session ({@link AutowiniClient}).
 * Prices are shown only to some members, so the admin usually enters the price on import.
 */
@Component
@Order(20)
public class AutowiniSourceAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(AutowiniSourceAdapter.class);

    private final AutowiniClient client;
    private final PageRenderer renderer;

    public AutowiniSourceAdapter(AutowiniClient client, PageRenderer renderer) {
        this.client = client;
        this.renderer = renderer;
    }

    @Override
    public CarSource source() {
        return CarSource.AUTOWINI;
    }

    @Override
    public boolean supports(URI url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
        return host.equals("autowini.com") || host.endsWith(".autowini.com");
    }

    @Override
    public URI canonicalUrl(URI url) {
        String id = AutowiniPageParser.itemId(url.toString());
        return id == null ? url : URI.create(AutowiniPageParser.desktopUrl(id));
    }

    @Override
    public String referer(URI listingUrl) {
        return "https://www.autowini.com/";
    }

    @Override
    public HttpClient imageClient() {
        return client.sessionClient();
    }

    @Override
    public List<String> imageCandidates(String imageUrl) {
        return AutowiniPageParser.imageCandidates(imageUrl);
    }

    /**
     * imagebox.autowini.com answers 403 (an autowini error page) to plain HTTP clients, even with the session
     * cookies, while a visitor's browser gets the photos. So the missing photos are loaded in headless Chromium,
     * on the listing page, with the session cookies.
     */
    @Override
    public Map<Integer, DownloadedImage> imagesFromBrowser(URI listingUrl, List<String> imageUrls) {
        if (!renderer.enabled()) {
            log.warn("autowini photos need the headless browser, which is off (APP_IMPORTER_BROWSER_ENABLED=false)");
            return Map.of();
        }
        List<List<String>> candidates = imageUrls.stream().map(AutowiniPageParser::imageCandidates).toList();
        Map<Integer, DownloadedImage> out = new HashMap<>();
        renderer.loadImages(listingUrl.toString(), candidates, client.browserCookies())
                .forEach((i, bytes) -> ImageDownloader.accept(bytes).ifPresent(img -> out.put(i, img)));
        return out;
    }

    @Override
    public ImportedCar importFrom(URI url) {
        String id = AutowiniPageParser.itemId(url.toString());
        if (id == null) {
            throw new BadRequestException("Not an autowini listing link (expected .../Cars/IC1234567/...)");
        }
        String html = client.fetchListing(AutowiniPageParser.desktopUrl(id), AutowiniPageParser.mobileUrl(id));
        ParsedPage page = AutowiniPageParser.parse(html, id);
        CarSpecs specs = AutowiniPageParser.toSpecs(page);

        List<String> warnings = new ArrayList<>();
        if (specs.fuelType() != null && specs.fuelType().hasTractionBattery()) {
            warnings.add("autowini does not list battery capacity or electric range: enter them manually.");
        }
        return new ImportedCar(page.itemId(), page.fields().get("location"), specs, page.price(), page.currency(),
                page.images(), warnings);
    }
}
