package com.premiumcars.autoimport;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.premiumcars.autoimport.AutoImportListingRepository.Listing;
import com.premiumcars.autoimport.AutoImportSettingsRepository.SourceSettings;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCar;
import com.premiumcars.catalog.web.dto.CarDtos.ImportResult;
import com.premiumcars.importing.ImportService;

/**
 * Imports the queued listings one at a time (guazi's first), with the same code as an import by hand but
 * {@value #MAX_PHOTOS} photos, and publishes each car. A car without a price or photos stays a draft. Works while
 * the main switch and the listing's source are on; a failure is retried up to 3 times, then again after the next run.
 */
@Component
public class AutoImportWorker {

    private static final Logger log = LoggerFactory.getLogger(AutoImportWorker.class);

    static final int MAX_PHOTOS = 20;
    /** Pause between two imports, on top of the sites' own pacing. */
    private static final long PAUSE_MS = 1_000;

    private final AutoImportSettingsRepository settings;
    private final AutoImportListingRepository listings;
    private final ImportService importService;
    private final CarService carService;
    private final AtomicBoolean busy = new AtomicBoolean();

    public AutoImportWorker(AutoImportSettingsRepository settings, AutoImportListingRepository listings,
                            ImportService importService, CarService carService) {
        this.settings = settings;
        this.listings = listings;
        this.importService = importService;
        this.carService = carService;
    }

    @Scheduled(fixedDelayString = "${app.auto-import.worker-delay:PT30S}",
            initialDelayString = "${app.auto-import.worker-initial-delay:PT1M}")
    public void work() {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            while (settings.enabled()) {
                Set<CarSource> on = settings.sources().stream().filter(SourceSettings::enabled)
                        .map(SourceSettings::source).collect(Collectors.toSet());
                Optional<Listing> next = listings.nextQueued(on);
                if (next.isEmpty()) {
                    break;
                }
                importOne(next.get());
                Thread.sleep(PAUSE_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            busy.set(false);
        }
    }

    void importOne(Listing l) {
        try {
            ImportResult r = importService.importAutomatically(l.url(), MAX_PHOTOS);
            AdminCar car = r.car();
            String note = null;
            if (car.status() == CarStatus.DRAFT) {
                if (car.price() == null) {
                    note = "No price found: left as a draft";
                } else if (car.images().isEmpty()) {
                    note = "No photos: left as a draft";
                } else {
                    carService.changeStatus(car.id(), CarStatus.PUBLISHED);
                }
            }
            listings.imported(l.source(), l.listingId(), car.id(), note);
            log.info("Auto-import {} {}: car {}{}", l.source(), l.listingId(), car.id(),
                    note == null ? " published" : " — " + note);
        } catch (RuntimeException e) {
            log.warn("Auto-import {} {} failed: {}", l.source(), l.listingId(), e.getMessage());
            listings.failed(l.source(), l.listingId(), e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
