package com.premiumcars.importing.source.autowini;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
import com.premiumcars.importing.browser.PageRenderer.BrowserImages;
import com.premiumcars.importing.browser.PageRenderer.CapturedImage;
import com.premiumcars.importing.source.autowini.AutowiniPageParser.ParsedPage;
import com.premiumcars.media.ImageDownloader;
import com.premiumcars.media.ImageDownloader.DownloadedImage;

/**
 * autowini.com (South Korea). Pages are read with a logged-in session ({@link AutowiniClient}).
 * Prices are shown only to some members: when the logged-in account may see it, the price is the one autowini
 * shows buyers (BIS price, USD, FOB Incheon), from the desktop page or (when that is refused) autowini's API;
 * otherwise the admin enters it on import.
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

    /** Photo files of a listing, wherever autowini serves them from; logos and icons are filtered out by size. */
    private static final Pattern PAGE_PHOTOS = Pattern.compile("(?i)(imagebox\\.autowini|/upload/)");
    private static final int MIN_PHOTO_BYTES = 15 * 1024;

    /**
     * imagebox.autowini.com answers 403 (an autowini error page) to plain HTTP clients, even with the session
     * cookies. So the missing photos are loaded in headless Chromium on the listing page, with the session
     * cookies: first the photos the page's own gallery loads, then the parsed URLs as {@code <img>} elements.
     */
    @Override
    public Map<Integer, DownloadedImage> imagesFromBrowser(URI listingUrl, List<String> imageUrls) {
        if (!renderer.enabled()) {
            log.warn("autowini photos need the headless browser, which is off (APP_IMPORTER_BROWSER_ENABLED=false)");
            return Map.of();
        }
        List<List<String>> candidates = imageUrls.stream().map(AutowiniPageParser::imageCandidates).toList();
        BrowserImages loaded = renderer.loadImages(listingUrl.toString(), candidates, client.browserCookies(),
                PAGE_PHOTOS);

        Map<Integer, DownloadedImage> out = new HashMap<>();
        loaded.requested().forEach((i, bytes) -> ImageDownloader.accept(bytes).ifPresent(img -> out.put(i, img)));
        if (out.size() < imageUrls.size()) {
            fillFromPage(out, imageUrls.size(), loaded.pageImages());
        }
        return out;
    }

    /** Puts the gallery photos the page loaded into the still empty slots, in page order, one per photo. */
    static void fillFromPage(Map<Integer, DownloadedImage> out, int slots, List<CapturedImage> pageImages) {
        Map<String, DownloadedImage> byPhoto = new LinkedHashMap<>();
        for (CapturedImage c : pageImages) {
            if (c.bytes().length < MIN_PHOTO_BYTES) {
                continue;
            }
            ImageDownloader.accept(c.bytes()).ifPresent(img -> byPhoto.merge(photoKey(c.url()), img,
                    (a, b) -> a.bytes().length >= b.bytes().length ? a : b)); // keep the larger size
        }
        Set<String> used = new HashSet<>();
        var photos = byPhoto.entrySet().iterator();
        for (int i = 0; i < slots && photos.hasNext(); i++) {
            if (!out.containsKey(i)) {
                var next = photos.next();
                if (used.add(next.getKey())) {
                    out.put(i, next.getValue());
                }
            }
        }
    }

    /**
     * autowini refuses the desktop page to the backend's HTTP client (403: the server's IP; a headless browser on
     * the server is refused too), and the mobile page has no price in its HTML: it reads it from autowini's API,
     * as {@link AutowiniClient#itemPrices} does. Without an API price the page stays as it is (entered by hand).
     */
    static ParsedPage withPrice(ParsedPage page, AutowiniClient.ApiPrices prices) {
        if (prices == null) {
            return page;
        }
        var discount = prices.discountedPrice() == null ? null : prices.bisPrice().subtract(prices.discountedPrice());
        return new ParsedPage(page.itemId(), page.fields(), prices.bisPrice(), "USD", discount, page.images());
    }

    /** ".../5d6db85f-..._1024.jpg" and ".../5d6db85f-..._320.jpg" are the same photo. */
    static String photoKey(String url) {
        String path = url.replaceAll("[?#].*$", "");
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.replaceAll("(?i)(_(320|640|1024|1280|org|orig))?\\.(jpe?g|png|webp|avif)$", "");
    }

    @Override
    public ImportedCar importFrom(URI url) {
        String id = AutowiniPageParser.itemId(url.toString());
        if (id == null) {
            throw new BadRequestException("Not an autowini listing link (expected .../Cars/IC1234567/...)");
        }
        String desktopUrl = AutowiniPageParser.desktopUrl(id);
        AutowiniClient.Listing listing = client.fetchListing(desktopUrl, AutowiniPageParser.mobileUrl(id));
        ParsedPage page = AutowiniPageParser.parse(listing.html(), id);
        if (listing.mobile() && page.price() == null) {
            page = withPrice(page, client.itemPrices(id));
        }
        CarSpecs specs = AutowiniPageParser.toSpecs(page);

        List<String> warnings = new ArrayList<>();
        if (specs.fuelType() != null && specs.fuelType().hasTractionBattery()) {
            warnings.add("autowini does not list battery capacity or electric range: enter them manually.");
        }
        if (page.discount() != null) {
            warnings.add("autowini shows a discount of USD " + page.discount().toPlainString()
                    + " on this car; the price above is without it.");
        }
        return new ImportedCar(page.itemId(), page.fields().get("location"), specs, page.price(), page.currency(),
                page.images(), warnings);
    }
}
