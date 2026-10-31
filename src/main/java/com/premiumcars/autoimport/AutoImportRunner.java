package com.premiumcars.autoimport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
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
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.catalog.repo.CarRepository.CheckedCar;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.social.PostedChannels;

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
 *   <li>checks the source's cars not covered by those listings (added by hand, or of makes no longer imported):
 *       their make is searched without the import's filters, and one missing in {@value #MISSES_TO_REMOVE} runs
 *       in a row is hidden too (same guard);</li>
 *   <li>notes every car it hides with where it was posted (Facebook, Instagram, Viber, mobile.bg);</li>
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
    private final PostedChannels posted;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-import-run");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<String> running = new AtomicReference<>();

    public AutoImportRunner(List<SourceSearch> searches, AutoImportSettingsRepository settings,
                            AutoImportListingRepository listings, AutoImportRunRepository runs, CarRepository cars,
                            CarService carService, AutoImportMailer mailer, PostedChannels posted) {
        searches.forEach(s -> this.searches.put(s.source(), s));
        this.settings = settings;
        this.listings = listings;
        this.runs = runs;
        this.cars = cars;
        this.carService = carService;
        this.mailer = mailer;
        this.posted = posted;
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
            Result result = pass(search, s, dryRun, runId);
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
        return pass(search, s, dryRun, null);
    }

    Result pass(SourceSearch search, SourceSettings s, boolean dryRun, Long runId) {
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
        int unpriced = 0;
        int returned = 0;
        for (FoundListing l : found.values()) {
            Optional<Listing> known = listings.find(source, l.id());
            Long carId = known.map(Listing::carId).orElse(null);
            Optional<Car> car = carId != null ? cars.findById(carId) : cars.findBySourceUrl(l.url());
            boolean back = car.map(c -> c.status() == CarStatus.REMOVED).orElse(false);
            boolean over = maxKm != null && l.mileageKm() != null && l.mileageKm() > maxKm;
            boolean priced = search.priced(l);
            if (known.isEmpty() && car.isEmpty() && !priced) {
                unpriced++; // at the source, but nothing to import it with (an auction): not queued
                continue;
            }
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
        if (unpriced > 0) {
            notes.add(unpriced + " without a price (auctions): not imported");
        }
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
                if (l.carId() != null && cars.markRemoved(l.carId())) {
                    noteRemoved(runId, source, l.carId(), false);
                }
                listings.delete(source, l.listingId());
                gone++;
            }
            purgeRemoved();
        }
        // the source's other cars (added by hand, or of makes not imported now)
        Set<Long> covered = tracked.stream().map(Listing::carId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        ManualCheck manual = checkOtherCars(search, source, covered, dryRun, runId, notes);
        if (manual.stopped() && status.equals("DONE")) {
            status = "STOPPED";
        }

        if (dryRun) {
            notes.add(0, "dry run: nothing was changed; " + carsToHide + " of the would-be removed are in our catalog");
        }
        return new Result(status, found.size(), newCount, gone, returned + manual.returned(), passes,
                notes.isEmpty() ? null : String.join("; ", notes), manual.checked(), manual.gone());
    }

    private static final SourceFilters NO_FILTERS = new SourceFilters(false, false, List.of());

    record ManualCheck(int checked, int gone, int returned, boolean stopped) {
    }

    /**
     * The source's cars not covered by the listings of makes read whole: their make (as the source names it) is
     * searched from the oldest car's year, without the import's filters. A car missing in {@value #MISSES_TO_REMOVE}
     * runs in a row is hidden; one back is published again. Makes not read whole, or not in the source's list, are
     * left alone.
     */
    ManualCheck checkOtherCars(SourceSearch search, CarSource source, Set<Long> covered, boolean dryRun, Long runId,
                               List<String> notes) {
        List<CheckedCar> others = cars.findForSourceCheck(source).stream()
                .filter(c -> !covered.contains(c.id()))
                .filter(c -> CHECKED_STATUSES.contains(c.status()))
                .toList();
        if (others.isEmpty()) {
            return new ManualCheck(0, 0, 0, false);
        }
        Map<String, CatalogBrand> byName = new HashMap<>();
        settings.catalog(source).forEach(b -> byName.putIfAbsent(key(b.name()), b));
        Map<String, List<CheckedCar>> byMake = others.stream()
                .collect(Collectors.groupingBy(c -> key(c.make()), LinkedHashMap::new, Collectors.toList()));

        int checked = 0;
        int returned = 0;
        int unknownMake = 0;
        List<CheckedCar> toRemove = new ArrayList<>();
        for (var group : byMake.entrySet()) {
            CatalogBrand make = byName.get(group.getKey());
            if (make == null) {
                unknownMake += group.getValue().size();
                continue;
            }
            int yearFrom = group.getValue().stream().map(CheckedCar::year).filter(java.util.Objects::nonNull)
                    .mapToInt(Integer::intValue).min().orElse(1990);
            Set<String> urls = new HashSet<>();
            BrandPass p = search.list(new Brand(make.code(), make.name(), true, Math.max(yearFrom, 1990), List.of()),
                    NO_FILTERS, l -> urls.add(l.url()));
            if (!p.complete()) {
                notes.add("cars added by hand, " + make.name() + ": "
                        + (p.error() == null ? "not read whole" : p.error()) + " (nothing of it removed)");
                continue;
            }
            for (CheckedCar c : group.getValue()) {
                checked++;
                if (urls.contains(c.sourceUrl())) {
                    if (!dryRun) {
                        listings.manualSeen(c.id());
                        if (c.status() == CarStatus.REMOVED && cars.restoreRemoved(c.id())) {
                            returned++;
                        }
                    } else if (c.status() == CarStatus.REMOVED) {
                        returned++;
                    }
                    continue;
                }
                if (c.status() == CarStatus.REMOVED) {
                    continue; // hidden already
                }
                int missed = dryRun ? listings.manualMissedSoFar(c.id()) + 1 : listings.manualMissed(c.id());
                if (missed >= MISSES_TO_REMOVE) {
                    toRemove.add(c);
                }
            }
        }
        if (unknownMake > 0) {
            notes.add(unknownMake + " cars added by hand of makes not in the source's list: not checked");
        }
        if (toRemove.size() > GUARD_MIN && toRemove.size() * 100L > (long) checked * MAX_GONE_PERCENT) {
            notes.add(0, "cars added by hand: " + toRemove.size() + " of " + checked + " would have been removed (over "
                    + MAX_GONE_PERCENT + "%): nothing was removed");
            if (!dryRun) {
                mailer.stopped(source.name(), "Ръчно добавени коли: " + toRemove.size() + " от " + checked
                        + " щяха да бъдат махнати (над " + MAX_GONE_PERCENT + "%). Не е махната нито една.");
            }
            return new ManualCheck(checked, 0, returned, true);
        }
        int gone = 0;
        for (CheckedCar c : toRemove) {
            if (dryRun) {
                gone++;
            } else if (cars.markRemoved(c.id())) {
                gone++;
                noteRemoved(runId, source, c.id(), true);
            }
        }
        if (checked > 0) {
            notes.add("cars added by hand: " + checked + " checked, " + gone + (dryRun ? " would be" : "")
                    + " removed");
        }
        return new ManualCheck(checked, gone, returned, false);
    }

    /** Statuses a check can change: hidden (from these three) or published again (REMOVED). */
    private static final Set<CarStatus> CHECKED_STATUSES =
            java.util.EnumSet.of(CarStatus.PUBLISHED, CarStatus.RESERVED, CarStatus.DRAFT, CarStatus.REMOVED);

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s-]+", " ");
    }

    /** The run's report: a hidden car with where it was posted, to take those posts down. */
    private void noteRemoved(Long runId, CarSource source, long carId, boolean manual) {
        cars.findById(carId).ifPresent(c -> runs.addRemoved(runId, source, carId, c.specs().title(), c.slug(),
                c.sourceUrl(), manual, posted.of(carId)));
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
