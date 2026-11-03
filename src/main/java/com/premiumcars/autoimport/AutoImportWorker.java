package com.premiumcars.autoimport;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
import com.premiumcars.importing.MileageOverLimitException;
import com.premiumcars.importing.NoPriceException;

/**
 * Imports the queued listings, each source's queue on its own thread (one listing at a time per source), with the
 * same code as an import by hand but {@value #MAX_PHOTOS} photos, and publishes each car. A car without photos stays a
 * draft; a listing without a FOB price is not saved (NO_PRICE: tried again next run, until it was so
 * {@value AutoImportListingRepository#NO_PRICE_RUNS} runs in a row); one over the mileage limit or the highest price
 * is not saved at all (SKIPPED, PRICE_SKIPPED). Works while the main switch and the listing's source are on; another
 * failure is retried up to 3 times, then again after the next run.
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
    /**
     * Each source's queue is imported on a thread of its own, side by side (one listing at a time per source, so a
     * listing is never imported twice), and never on the scheduler's threads: a long queue (days) must not hold the
     * nightly run, the scheduled posts or the exchange rates.
     */
    private final Map<CarSource, ExecutorService> executors = new ConcurrentHashMap<>();
    private final Set<CarSource> busy = ConcurrentHashMap.newKeySet();
    private final PriceLimit priceLimit;

    public AutoImportWorker(AutoImportSettingsRepository settings, AutoImportListingRepository listings,
                            ImportService importService, CarService carService, PriceLimit priceLimit) {
        this.settings = settings;
        this.listings = listings;
        this.importService = importService;
        this.carService = carService;
        this.priceLimit = priceLimit;
    }

    @Scheduled(fixedDelayString = "${app.auto-import.worker-delay:PT30S}",
            initialDelayString = "${app.auto-import.worker-initial-delay:PT1M}")
    public void work() {
        if (!settings.enabled()) {
            return;
        }
        for (SourceSettings s : settings.sources()) {
            CarSource source = s.source();
            if (!s.enabled() || !busy.add(source)) {
                continue; // off, or still importing
            }
            try {
                executors.computeIfAbsent(source, x -> Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "auto-import-worker-" + x.name().toLowerCase(java.util.Locale.ROOT));
                    t.setDaemon(true);
                    return t;
                })).submit(() -> drain(source));
            } catch (RuntimeException e) {
                busy.remove(source);
                throw e;
            }
        }
    }

    /** Imports the source's queue one listing at a time until it is empty or the source is turned off. */
    void drain(CarSource source) {
        try {
            while (settings.enabled() && settings.source(source).map(SourceSettings::enabled).orElse(false)) {
                Optional<Listing> next = listings.nextQueued(List.of(source));
                if (next.isEmpty()) {
                    break;
                }
                importOne(next.get());
                Thread.sleep(PAUSE_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Auto-import {} queue stopped (tried again in a moment): {}", source, e.toString());
        } finally {
            busy.remove(source);
        }
    }

    void importOne(Listing l) {
        Integer maxKm = settings.maxMileageKm();
        if (maxKm != null && l.mileageKm() != null && l.mileageKm() > maxKm) {
            listings.skipped(l.source(), l.listingId(), l.mileageKm(), "Over " + maxKm + " km: skipped");
            return;
        }
        Integer maxEur = priceLimit.maxEur();
        if (priceLimit.over(l.price(), "USD", maxEur)) {
            listings.priceSkipped(l.source(), l.listingId(), "Over " + maxEur + " EUR FOB: skipped");
            return;
        }
        try {
            ImportResult r = importService.importAutomatically(l.url(), MAX_PHOTOS, maxKm);
            AdminCar car = r.car();
            if (car.price() == null && !r.alreadyImported()) {
                // no FOB price on the listing page: skipped, not put on the site with a wrong or missing price
                carService.delete(car.id());
                listings.noPrice(l.source(), l.listingId(), "No FOB price on the listing: skipped");
                log.info("Auto-import {} {}: no FOB price, skipped", l.source(), l.listingId());
                return;
            }
            String note = null;
            if (car.status() == CarStatus.DRAFT) {
                if (car.images().isEmpty()) {
                    note = "No photos: left as a draft";
                } else {
                    carService.changeStatus(car.id(), CarStatus.PUBLISHED);
                }
            }
            listings.imported(l.source(), l.listingId(), car.id(), note);
            log.info("Auto-import {} {}: car {}{}", l.source(), l.listingId(), car.id(),
                    note == null ? " published" : " — " + note);
        } catch (NoPriceException e) {
            listings.noPrice(l.source(), l.listingId(), e.getMessage());
            log.info("Auto-import {} {}: no FOB price", l.source(), l.listingId());
        } catch (MileageOverLimitException e) {
            listings.skipped(l.source(), l.listingId(), e.mileageKm(), e.getMessage());
            log.info("Auto-import {} {}: {}", l.source(), l.listingId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Auto-import {} {} failed: {}", l.source(), l.listingId(), e.getMessage());
            listings.failed(l.source(), l.listingId(), e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
