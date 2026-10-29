package com.premiumcars.autoimport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.premiumcars.autoimport.AutoImportListingRepository.Listing;
import com.premiumcars.autoimport.AutoImportRunRepository.Result;
import com.premiumcars.autoimport.AutoImportSettingsRepository.SourceSettings;
import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.BrandPass;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.catalog.service.CarService;

/**
 * A run of the automatic import for one source: lists every listing of the chosen makes, then
 * <ul>
 *   <li>queues the new ones ({@link AutoImportWorker} imports them), except those over the mileage limit when the
 *       search shows their mileage (kept as SKIPPED), and links listings of cars already in our
 *       catalog (also ones imported by hand), publishing again a car hidden as REMOVED whose listing is back;</li>
 *   <li>hides (REMOVED) the cars whose listing was missing in {@value #MISSES_TO_REMOVE} runs in a row, counting
 *       only makes read whole: a listing can slip between result pages while the site changes;</li>
 *   <li>stops without removing anything when more than {@value #MAX_GONE_PERCENT}% of a source's cars would go
 *       (a site change or a bad night), and e-mails the admin;</li>
 *   <li>deletes the cars hidden for {@value #KEEP_REMOVED_DAYS} days.</li>
 * </ul>
 * A dry run only counts. Runs go one at a time, nightly at 03:00 Sofia time (guazi first) while the main switch is
 * on, or when an admin starts one.
 */
@Component
public class AutoImportRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoImportRunner.class);

    static final int MISSES_TO_REMOVE = 2;
    static final int MAX_GONE_PERCENT = 20;
    /** Below this many removals the share does not matter (a small make losing a few cars is normal). */
    static final int GUARD_MIN = 20;
    static final int KEEP_REMOVED_DAYS = 30;

    private final Map<CarSource, SourceSearch> searches = new EnumMap<>(CarSource.class);
    private final AutoImportSettingsRepository settings;
    private final AutoImportListingRepository listings;
    private final AutoImportRunRepository runs;
    private final CarRepository cars;
    private final CarService carService;
    private final AutoImportMailer mailer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-import-run");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<String> running = new AtomicReference<>();

    public AutoImportRunner(List<SourceSearch> searches, AutoImportSettingsRepository settings,
                            AutoImportListingRepository listings, AutoImportRunRepository runs, CarRepository cars,
                            CarService carService, AutoImportMailer mailer) {
        searches.forEach(s -> this.searches.put(s.source(), s));
        this.settings = settings;
        this.listings = listings;
        this.runs = runs;
        this.cars = cars;
        this.carService = carService;
        this.mailer = mailer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void abandonInterruptedRuns() {
        runs.abandonRunning();
    }

    @Scheduled(cron = "${app.auto-import.cron:0 0 3 * * *}", zone = "Europe/Sofia")
    public void nightly() {
        if (!settings.enabled()) {
            return;
        }
        for (SourceSettings s : settings.sources()) { // guazi first
            if (s.enabled()) {
                submit(s.source(), false);
            }
        }
    }

    /** Queues a run; runs go one at a time. */
    public void submit(CarSource source, boolean dryRun) {
        executor.submit(() -> run(source, dryRun));
    }

    /** What is running now ("GUAZI", "AUTOWINI (dry run)"), or null. */
    public String running() {
        return running.get();
    }

    void run(CarSource source, boolean dryRun) {
        SourceSearch search = searches.get(source);
        SourceSettings s = settings.source(source).orElse(null);
        if (search == null || s == null) {
            return;
        }
        running.set(source + (dryRun ? " (dry run)" : ""));
        long runId = runs.start(source, dryRun);
        log.info("Auto-import {}{}: started", source, dryRun ? " (dry run)" : "");
        try {
            refreshCatalog(search);
            Result result = pass(search, s, dryRun);
            runs.finish(runId, result);
            log.info("Auto-import {}{}: {} — found {}, new {}, gone {}, back {}{}", source,
                    dryRun ? " (dry run)" : "", result.status(), result.found(), result.newCount(),
                    result.goneCount(), result.returnedCount(),
                    result.message() == null ? "" : " — " + result.message());
        } catch (RuntimeException e) {
            log.warn("Auto-import {} failed", source, e);
            runs.finish(runId, new Result("FAILED", 0, 0, 0, 0, List.of(), e.toString()));
        } finally {
            running.set(null);
        }
    }

    private void refreshCatalog(SourceSearch search) {
        try {
            settings.saveCatalog(search.source(), search.brands());
        } catch (RuntimeException e) {
            log.warn("Auto-import {}: could not refresh the makes: {}", search.source(), e.getMessage());
        }
    }

    Result pass(SourceSearch search, SourceSettings s, boolean dryRun) {
        CarSource source = s.source();
        List<Brand> brands = settings.brands(source).stream().filter(Brand::enabled).toList();
        Map<String, FoundListing> found = new LinkedHashMap<>();
        Map<String, String> brandOf = new HashMap<>();
        List<BrandPass> passes = new ArrayList<>();
        for (Brand brand : brands) {
            BrandPass p = search.list(brand, s.filters(), l -> {
                if (found.putIfAbsent(l.id(), l) == null) {
                    brandOf.put(l.id(), brand.code());
                }
            });
            passes.add(p);
        }
        Set<String> complete = passes.stream().filter(BrandPass::complete).map(BrandPass::code)
                .collect(Collectors.toSet());

        // new, seen again, back
        Integer maxKm = settings.maxMileageKm();
        int newCount = 0;
        int overMileage = 0;
        int returned = 0;
        for (FoundListing l : found.values()) {
            Optional<Listing> known = listings.find(source, l.id());
            Long carId = known.map(Listing::carId).orElse(null);
            Optional<Car> car = carId != null ? cars.findById(carId) : cars.findBySourceUrl(l.url());
            boolean back = car.map(c -> c.status() == CarStatus.REMOVED).orElse(false);
            boolean over = maxKm != null && l.mileageKm() != null && l.mileageKm() > maxKm;
            if (car.isEmpty() && over) {
                overMileage++;
            } else if (known.isEmpty() && car.isEmpty()) {
                newCount++;
            }
            if (back) {
                returned++;
            }
            if (dryRun) {
                continue;
            }
            if (known.isPresent()) {
                listings.seen(source, l, brandOf.get(l.id()), maxKm);
            } else if (car.isPresent()) {
                listings.insert(source, l, brandOf.get(l.id()), AutoImportListingRepository.IMPORTED,
                        car.get().id());
            } else {
                listings.insert(source, l, brandOf.get(l.id()),
                        over ? AutoImportListingRepository.SKIPPED : AutoImportListingRepository.QUEUED, null);
            }
            if (back) {
                cars.restoreRemoved(car.get().id());
            }
        }

        // gone: only makes read whole
        List<Listing> tracked = listings.ofBrands(source, complete);
        List<Listing> missing = tracked.stream().filter(l -> !found.containsKey(l.listingId())).toList();
        List<Listing> toRemove = missing.stream().filter(l -> l.missedPasses() + 1 >= MISSES_TO_REMOVE).toList();
        long carsToHide = toRemove.stream().filter(l -> l.carId() != null).count();

        List<String> notes = new ArrayList<>();
        passes.stream().filter(p -> !p.complete())
                .forEach(p -> notes.add(p.name() + ": " + (p.error() == null ? "not read whole" : p.error())
                        + " (nothing of it removed)"));
        if (overMileage > 0) {
            notes.add(overMileage + " over " + maxKm + " km (not imported)");
        }
        String status = complete.size() == brands.size() ? "DONE" : "INCOMPLETE";
        int gone = 0;

        if (toRemove.size() > GUARD_MIN && toRemove.size() * 100L > (long) tracked.size() * MAX_GONE_PERCENT) {
            status = "STOPPED";
            String reason = toRemove.size() + " of " + tracked.size() + " listings would have been removed (over "
                    + MAX_GONE_PERCENT + "%): nothing was removed";
            notes.add(0, reason);
            if (!dryRun) {
                mailer.stopped(source.name(), toRemove.size() + " от " + tracked.size()
                        + " обяви щяха да бъдат махнати (над " + MAX_GONE_PERCENT + "%). Не е махната нито една.");
            }
        } else if (dryRun) {
            gone = toRemove.size();
            if (missing.size() > toRemove.size()) {
                notes.add((missing.size() - toRemove.size()) + " missing for the first time (removed if missing again)");
            }
        } else {
            for (Listing l : missing) {
                if (l.missedPasses() + 1 < MISSES_TO_REMOVE) {
                    listings.missed(source, l.listingId());
                    continue;
                }
                if (l.carId() != null) {
                    cars.markRemoved(l.carId());
                }
                listings.delete(source, l.listingId());
                gone++;
            }
            purgeRemoved();
        }
        if (dryRun) {
            notes.add(0, "dry run: nothing was changed; " + carsToHide + " of the would-be removed are in our catalog");
        }
        return new Result(status, found.size(), newCount, gone, returned, passes,
                notes.isEmpty() ? null : String.join("; ", notes));
    }

    /** Deletes the cars hidden as REMOVED for {@value #KEEP_REMOVED_DAYS} days (their photos too). */
    void purgeRemoved() {
        for (Long id : cars.findRemovedBefore(Instant.now().minus(Duration.ofDays(KEEP_REMOVED_DAYS)))) {
            try {
                carService.delete(id);
            } catch (RuntimeException e) {
                log.warn("Could not delete removed car {}: {}", id, e.toString());
            }
        }
    }
}
