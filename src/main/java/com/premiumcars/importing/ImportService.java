package com.premiumcars.importing;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.NewCarImage;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCar;
import com.premiumcars.catalog.web.dto.CarDtos.ImportResult;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.config.AppProperties;
import com.premiumcars.media.ImageDownloader;
import com.premiumcars.media.ImageDownloader.DownloadedImage;
import com.premiumcars.media.ImageStorage;

/**
 * "Import by link": fetch the page, extract data, create a DRAFT car, download its photos. Sites whose photos load in
 * any browser ({@link SourceAdapter#linksPhotos()}) keep them linked: each photo is downloaded once to find the variant
 * that works, and only its URL is saved.
 * Not transactional on purpose: network calls happen outside database transactions.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final List<SourceAdapter> adapters;
    private final CarService carService;
    private final ImageDownloader downloader;
    private final ImageStorage storage;
    private final AppProperties props;
    /**
     * How many photos of one listing are downloaded (and stored) at a time; below 1 = 1, which downloads them one by
     * one. autowini's bot protection refuses a server downloading too fast: if it does, slow down again with this
     * setting and {@link #photoPause}.
     */
    @org.springframework.beans.factory.annotation.Value("${app.importer.photo-parallel:4}")
    private int photoParallel = 4;
    /** At least this long between the starts of two photo downloads of an autowini listing (0 = no pause). */
    @org.springframework.beans.factory.annotation.Value("${app.importer.photo-pause:PT0S}")
    private java.time.Duration photoPause = java.time.Duration.ZERO;

    public ImportService(List<SourceAdapter> adapters, CarService carService, ImageDownloader downloader,
                         ImageStorage storage, AppProperties props) {
        this.adapters = adapters; // ordered by @Order, the generic fallback is last
        this.carService = carService;
        this.downloader = downloader;
        this.storage = storage;
        this.props = props;
    }

    /**
     * @param price    optional price that replaces the one on the page (autowini usually hides it)
     * @param currency currency of {@code price}; EUR when empty
     * @param html     the page as the admin's browser saved it, or null to download it
     */
    public ImportResult importFromUrl(String rawUrl, BigDecimal price, String currency, String html) {
        return importFromUrl(rawUrl, price, currency, html, props.importer().maxImages(), null);
    }

    /**
     * The automatic import: a listing URL from a source's search, with fewer photos than an import by hand.
     *
     * @param maxMileageKm a listing with more km is not saved ({@link MileageOverLimitException}); null = no limit
     */
    public ImportResult importAutomatically(String listingUrl, int maxImages, Integer maxMileageKm) {
        return importAutomatically(listingUrl, maxImages, maxMileageKm, null);
    }

    /**
     * @param menuModel the source's own model the search found the listing under (autowini's menu entry, e.g.
     *                  "5 Series"): the car's model, instead of the page's wording ("All New 5 Series"); null = the
     *                  page's
     */
    public ImportResult importAutomatically(String listingUrl, int maxImages, Integer maxMileageKm,
                                            String menuModel) {
        return importFromUrl(listingUrl, null, null, null, Math.min(maxImages, props.importer().maxImages()),
                maxMileageKm, menuModel);
    }

    /**
     * Reads a listing again without saving anything (no photos): for a recalculation from the source's current price.
     * Throws when the site has no importer or the page cannot be read.
     */
    public ImportedCar readListing(String rawUrl) {
        URI url = normalize(rawUrl);
        SourceAdapter adapter = adapters.stream()
                .filter(a -> a.supports(url))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("No importer for " + url.getHost()));
        return adapter.importFrom(adapter.canonicalUrl(url));
    }

    private ImportResult importFromUrl(String rawUrl, BigDecimal price, String currency, String html, int maxImages,
                                       Integer maxMileageKm) {
        return importFromUrl(rawUrl, price, currency, html, maxImages, maxMileageKm, null);
    }

    private ImportResult importFromUrl(String rawUrl, BigDecimal price, String currency, String html, int maxImages,
                                       Integer maxMileageKm, String menuModel) {
        URI url = normalize(rawUrl);
        SourceAdapter adapter = adapters.stream()
                .filter(a -> a.supports(url))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("No importer for " + url.getHost()));
        URI canonical = adapter.canonicalUrl(url);
        String key = canonical.toString();

        var existing = carService.findListing(adapter.source(), key, adapter.listingRef(canonical));
        if (existing.isPresent()) {
            return new ImportResult(existing.get(), true, 0, List.of("This link was already imported."));
        }
        log.info("Importing {} with {}", key, adapter.getClass().getSimpleName());

        ImportedCar data = html == null || html.isBlank()
                ? adapter.importFrom(canonical)
                : adapter.importFromHtml(canonical, html);
        if (price != null) {
            String cur = currency == null || currency.isBlank() ? "EUR" : currency.trim().toUpperCase(Locale.ROOT);
            data = new ImportedCar(data.sourceRef(), data.sourceLocation(), data.specs(), price, cur,
                    data.imageUrls(), data.warnings());
        }
        if (menuModel != null && !menuModel.isBlank()) {
            data = new ImportedCar(data.sourceRef(), data.sourceLocation(),
                    data.specs().toBuilder().model(menuModel.trim()).build(), data.price(), data.currency(),
                    data.imageUrls(), data.warnings());
        }
        Integer km = data.specs().mileageKm();
        if (maxMileageKm != null && km != null && km > maxMileageKm) {
            throw new MileageOverLimitException(km, maxMileageKm); // before anything is saved or downloaded
        }
        if ((data.price() == null || data.price().signum() <= 0) && adapter.requiresPrice()) {
            throw new NoPriceException("No price could be read from the listing (" + adapter.source()
                    + (adapter.source() == CarSource.GUAZI ? ": no fixed FOB price, an auction?" : "")
                    + "), so it was not imported. To import it anyway, enter the price in the import form.");
        }
        List<String> warnings = new ArrayList<>(data.warnings());
        addGeneralWarnings(data, warnings);

        AdminCar draft = carService.createDraft(data, adapter.source(), key);

        List<String> urls = data.imageUrls().stream().limit(maxImages).toList();
        DownloadedImage[] photos = new DownloadedImage[urls.size()];
        String[] working = new String[urls.size()]; // the candidate URL that downloaded
        List<Fetched> fetched = downloadPhotos(adapter, adapter.referer(canonical), urls);
        for (int i = 0; i < urls.size(); i++) {
            if (fetched.get(i) != null) {
                photos[i] = fetched.get(i).image();
                working[i] = fetched.get(i).url();
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < photos.length; i++) {
            if (photos[i] == null) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            List<String> missingUrls = missing.stream().map(urls::get).toList();
            adapter.imagesFromBrowser(canonical, missingUrls)
                    .forEach((k, img) -> photos[missing.get(k)] = img);
        }
        List<NewCarImage> images = storePhotos(adapter, draft.id(), urls, photos, working);
        if (images.size() < urls.size()) {
            warnings.add("Downloaded " + images.size() + " of " + urls.size() + " photos.");
            log.warn("Downloaded {} of {} photos for {}; last refusal: {}", images.size(), urls.size(), key,
                    downloader.lastRefusal());
        }
        AdminCar result = images.isEmpty() ? draft : carService.attachImages(draft.id(), images);
        return new ImportResult(result, false, images.size(), warnings);
    }

    /** A downloaded photo and the candidate URL it came from. */
    private record Fetched(DownloadedImage image, String url) {
    }

    /** Each photo's first candidate that downloads, by index (null = none did), {@link #photoParallel} at a time. */
    private List<Fetched> downloadPhotos(SourceAdapter adapter, String referer, List<String> urls) {
        var imageClient = adapter.imageClient();
        var gap = adapter.source() == CarSource.AUTOWINI ? photoPause : java.time.Duration.ZERO;
        return PhotoTasks.run(urls.size(), photoParallel, gap, i -> {
            for (String candidate : adapter.imageCandidates(urls.get(i))) {
                Optional<DownloadedImage> img = downloader.download(candidate, referer, imageClient);
                if (img.isPresent()) {
                    return new Fetched(img.get(), candidate);
                }
            }
            return null;
        });
    }

    /** The photos in their order: linked ones by their URL, the others stored ({@link #photoParallel} at a time). */
    private List<NewCarImage> storePhotos(SourceAdapter adapter, Long carId, List<String> urls,
                                          DownloadedImage[] photos, String[] working) {
        List<String> stored = PhotoTasks.run(photos.length, photoParallel, java.time.Duration.ZERO, i ->
                photos[i] == null || adapter.linksPhotos() && working[i] != null ? null
                        : storage.store("cars/" + carId, photos[i].bytes(), photos[i].extension()));
        List<NewCarImage> images = new ArrayList<>();
        for (int i = 0; i < photos.length; i++) {
            if (photos[i] == null) {
                continue;
            }
            if (adapter.linksPhotos() && working[i] != null) {
                images.add(new NewCarImage(null, working[i]));
            } else {
                images.add(new NewCarImage(stored.get(i), urls.get(i)));
            }
        }
        return images;
    }

    private static void addGeneralWarnings(ImportedCar d, List<String> w) {
        if (d.price() == null) {
            w.add(0, "Price not found: enter it on the car page (or in the import form next time).");
        }
        if (d.specs().year() == null) {
            w.add("Year not found.");
        }
        if (d.specs().mileageKm() == null) {
            w.add("Mileage not found.");
        }
        if (d.imageUrls().isEmpty()) {
            w.add("No photos found on the page.");
        }
    }

    /** Validates the URL and drops the #fragment so the same listing is not imported twice. */
    static URI normalize(String rawUrl) {
        try {
            URI uri = new URI(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
                throw new BadRequestException("Enter a full http(s) link to the car page");
            }
            return new URI("https", uri.getUserInfo(), uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(),
                    uri.getPath(), uri.getQuery(), null);
        } catch (URISyntaxException e) {
            throw new BadRequestException("Invalid link: " + rawUrl, e);
        }
    }
}
