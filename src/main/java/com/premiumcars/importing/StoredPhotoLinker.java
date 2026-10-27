package com.premiumcars.importing;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarImage;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.repo.CarImageRepository;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.media.ImageDownloader;
import com.premiumcars.media.ImageStorage;

/**
 * Turns the stored photos of sites whose photos are now linked ({@link SourceAdapter#linksPhotos()}: guazi, autohome)
 * into linked photos, once, in the background after startup: a photo whose source URL still loads is linked and its
 * file deleted; one that no longer loads stays stored. Nothing is left to do on later starts.
 * Off with {@code app.importer.link-stored-photos=false} (tests).
 */
@Component
@ConditionalOnProperty(name = "app.importer.link-stored-photos", havingValue = "true", matchIfMissing = true)
public class StoredPhotoLinker {

    private static final Logger log = LoggerFactory.getLogger(StoredPhotoLinker.class);
    /** Pause between two photos: gentle on the source's CDN. */
    private static final long PAUSE_MS = 200;

    private final List<SourceAdapter> adapters;
    private final CarImageRepository images;
    private final CarRepository cars;
    private final ImageDownloader downloader;
    private final ImageStorage storage;

    public StoredPhotoLinker(List<SourceAdapter> adapters, CarImageRepository images, CarRepository cars,
                             ImageDownloader downloader, ImageStorage storage) {
        this.adapters = adapters;
        this.images = images;
        this.cars = cars;
        this.downloader = downloader;
        this.storage = storage;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.ofVirtual().name("stored-photo-linker").start(this::run);
    }

    void run() {
        Map<CarSource, SourceAdapter> linking = new HashMap<>();
        adapters.stream().filter(SourceAdapter::linksPhotos).forEach(a -> linking.putIfAbsent(a.source(), a));
        if (linking.isEmpty()) {
            return;
        }
        List<CarImage> stored = images.findStored(linking.keySet().stream().map(Enum::name).toList());
        if (stored.isEmpty()) {
            return;
        }
        log.info("Linking {} stored photos of {}", stored.size(), linking.keySet());
        Map<Long, Optional<CarSource>> sourceOfCar = new HashMap<>();
        int linked = 0;
        for (CarImage image : stored) {
            try {
                CarSource source = sourceOfCar
                        .computeIfAbsent(image.carId(), id -> cars.findById(id).map(Car::source))
                        .orElse(null);
                SourceAdapter adapter = linking.get(source);
                if (adapter != null && link(image, adapter)) {
                    linked++;
                }
                Thread.sleep(PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.warn("Could not link photo {}: {}", image.id(), e.toString());
            }
        }
        log.info("Linked {} of {} stored photos; the others stay stored", linked, stored.size());
    }

    private boolean link(CarImage image, SourceAdapter adapter) {
        String referer = adapter.referer(URI.create(image.sourceUrl()));
        for (String candidate : adapter.imageCandidates(image.sourceUrl())) {
            if (downloader.download(candidate, referer, adapter.imageClient()).isPresent()) {
                images.link(image.id(), candidate);
                storage.delete(image.storagePath());
                return true;
            }
        }
        return false;
    }
}
