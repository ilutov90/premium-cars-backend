package com.premiumcars.importing;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.premiumcars.catalog.domain.NewCarImage;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCar;
import com.premiumcars.catalog.web.dto.CarDtos.ImportResult;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.media.ImageDownloader;
import com.premiumcars.media.ImageStorage;

/**
 * "Import by link": fetch the page, extract data, create a DRAFT car, download its photos.
 * Not transactional on purpose: network calls happen outside database transactions.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final List<SourceAdapter> adapters;
    private final CarService carService;
    private final ImageDownloader downloader;
    private final ImageStorage storage;

    public ImportService(List<SourceAdapter> adapters, CarService carService, ImageDownloader downloader,
                         ImageStorage storage) {
        this.adapters = adapters; // ordered by @Order, the generic fallback is last
        this.carService = carService;
        this.downloader = downloader;
        this.storage = storage;
    }

    public ImportResult importFromUrl(String rawUrl) {
        URI url = normalize(rawUrl);
        String key = url.toString();

        var existing = carService.findBySourceUrl(key);
        if (existing.isPresent()) {
            return new ImportResult(existing.get(), true, 0, List.of("This link was already imported."));
        }

        SourceAdapter adapter = adapters.stream()
                .filter(a -> a.supports(url))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("No importer for " + url.getHost()));
        log.info("Importing {} with {}", url, adapter.getClass().getSimpleName());

        ImportedCar data = adapter.importFrom(url);
        List<String> warnings = warningsFor(data);

        AdminCar draft = carService.createDraft(data, adapter.source(), key);

        List<NewCarImage> images = new ArrayList<>();
        for (String imageUrl : data.imageUrls()) {
            downloader.download(imageUrl, key).ifPresent(img -> {
                String path = storage.store("cars/" + draft.id(), img.bytes(), img.extension());
                images.add(new NewCarImage(path, imageUrl));
            });
        }
        if (images.size() < data.imageUrls().size()) {
            warnings.add("Downloaded " + images.size() + " of " + data.imageUrls().size() + " photos.");
        }
        AdminCar result = images.isEmpty() ? draft : carService.attachImages(draft.id(), images);
        return new ImportResult(result, false, images.size(), warnings);
    }

    private static List<String> warningsFor(ImportedCar d) {
        List<String> w = new ArrayList<>();
        if (d.price() == null) {
            w.add("Price not found — enter it manually.");
        }
        if (d.year() == null) {
            w.add("Year not found.");
        }
        if (d.mileageKm() == null) {
            w.add("Mileage not found.");
        }
        if (d.imageUrls().isEmpty()) {
            w.add("No photos found on the page.");
        }
        return w;
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
