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
import java.util.concurrent.ConcurrentHashMap;
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
import com.premiumcars.catalog.domain.MakeNames;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.catalog.repo.CarRepository.CheckedCar;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.social.PostedChannels;

/**
 * A run of the automatic import for one source: lists every listing of the chosen makes, then
 * <ul>
 *   <li>queues the new ones ({@link AutoImportWorker} imports them), except those over the mileage limit when the
 *       search shows their mileage (kept as SKIPPED), and links listings of cars already in our
 *       catalog (also ones imported by hand);</li>
 *   <li>deletes the cars whose listing is gone: missing from a make read whole, or, in a make the site did not
 *       show whole (more results than it serves), not seen for {@value #STALE_DAYS} days; a make the site stopped
 *       answering for counts nothing as gone. A gone listing does not come back (guazi relists under another link
 *       and id). Reserved, sold and archived cars are kept;</li>
 *   <li>stops without deleting anything when more than {@value #MAX_GONE_PERCENT}% of a source's cars would go
 *       (a site change or a bad night), and e-mails the admin;</li>
 *   <li>checks the source's cars not covered by those listings (added by hand, or of makes no longer imported):
 *       their make is searched without the import's filters (by link or listing id), and one missing is deleted
 *       too (same guard);</li>
 *   <li>notes every car it deletes with where it was posted (Facebook, Instagram, Viber, mobile.bg, cars.bg), to
 *       take those posts down;</li>
 *   <li>deletes the cars an earlier version hid as REMOVED.</li>
 * </ul>
 * A dry run only counts. Runs go one at a time, nightly at 03:00 Sofia time (guazi first) while the main switch is
 * on, or when an admin starts one.
 */
@Component
public class AutoImportRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoImportRunner.class);

    /** In a make not read whole, a listing not seen for this long is gone. */
    static final int STALE_DAYS = 2;
    static final int MAX_GONE_PERCENT = 20;
    /** Below this many removals the share does not matter (a small make losing a few cars is normal). */
    static final int GUARD_MIN = 20;
    /** The statuses a gone listing deletes; reserved, sold and archived cars stay. */
    static final Set<CarStatus> DELETED_WHEN_GONE =
            java.util.EnumSet.of(CarStatus.PUBLISHED, CarStatus.DRAFT, CarStatus.REMOVED);

    private final Map<CarSource, SourceSearch> searches = new EnumMap<>(CarSource.class);
    private final AutoImportSettingsRepository settings;
    private final AutoImportListingRepository listings;
    private final AutoImportRunRepository runs;
    private final CarRepository cars;
    private final CarService carService;
    private final AutoImportMailer mailer;
    private final PostedChannels posted;
    /** One thread per source: the sites' runs go side by side, two runs of one site never. */
    private final Map<CarSource, ExecutorService> executors = new ConcurrentHashMap<>();
    /** The sources with a run going on or waiting to start, and what it is ("GUAZI", "AUTOWINI (dry run)"). */
    private final Map<CarSource, String> running = new ConcurrentHashMap<>();
    private final PriceLimit priceLimit;
    private final AutoImportInstance instance;

    public AutoImportRunner(List<SourceSearch> searches, AutoImportSettingsRepository settings,
                            AutoImportListingRepository listings, AutoImportRunRepository runs, CarRepository cars,
                            CarService carService, AutoImportMailer mailer, PostedChannels posted,
                            PriceLimit priceLimit, AutoImportInstance instance) {
        this.instance = instance;
        searches.forEach(s -> this.searches.put(s.source(), s));
        this.settings = settings;
        this.listings = listings;
        this.runs = runs;
        this.cars = cars;
        this.carService = carService;
        this.mailer = mailer;
        this.posted = posted;
        this.priceLimit = priceLimit;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void abandonInterruptedRuns() {
        if (instance.enabled()) { // a run marked RUNNING may be the other server's
            runs.abandonRunning();
        }
    }

    /**
     * Each enabled source's run, side by side. A source whose previous run (the search, not its import queue) is
     * still going on is left out tonight; its queue waits while the run reads the site (AutoImportWorker).
     */
    @Scheduled(cron = "${app.auto-import.cron:0 0 3 * * *}", zone = "Europe/Sofia")
    public void nightly() {
        if (!instance.enabled() || !settings.enabled()) { // another server runs the import, or it is off
            return;
        }
        for (SourceSettings s : settings.sources()) {
            if (s.enabled() && !submit(s.source(), false)) {
                log.warn("Auto-import {}: the previous run is still going on; tonight's run left out", s.source());
            }
        }
    }

    /**
     * Starts a run of the source in the background, unless one of it is going on or waiting.
     *
     * @return false when the source has a run already
     */
    public boolean submit(CarSource source, boolean dryRun) {
        if (!instance.enabled()) {
            throw new IllegalStateException("The automatic import does not run on this server");
        }
        if (running.putIfAbsent(source, source + (dryRun ? " (dry run)" : "")) != null) {
            return false;
        }
        try {
            executors.computeIfAbsent(source, s -> Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "auto-import-run-" + s.name().toLowerCase(java.util.Locale.ROOT));
                t.setDaemon(true);
                return t;
            })).submit(() -> {
                try {
                    run(source, dryRun);
                } finally {
                    running.remove(source);
                }
            });
            return true;
        } catch (RuntimeException e) {
            running.remove(source);
            throw e;
        }
    }

    /** What is running now ("GUAZI", "GUAZI, AUTOWINI (dry run)"), or null. */
    public String running() {
        return running.isEmpty() ? null : running.values().stream().sorted().collect(Collectors.joining(", "));
    }

    public boolean running(CarSource source) {
        return running.containsKey(source);
    }

    void run(CarSource source, boolean dryRun) {
        SourceSearch search = searches.get(source);
        SourceSettings s = settings.source(source).orElse(null);
        if (search == null || s == null) {
            return;
        }
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
        if (!dryRun) {
            purgeRemoved();
        }
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
        Set<String> answered = passes.stream().filter(p -> !p.failed()).map(BrandPass::code)
                .collect(Collectors.toSet());

        // new, seen again, back
        Integer maxKm = settings.maxMileageKm();
        Integer maxEur = priceLimit.maxEur();
        int newCount = 0;
        int overMileage = 0;
        int overPrice = 0;
        int unpriced = 0;
        int returned = 0;
        int renamed = 0;
        for (FoundListing l : found.values()) {
            Optional<Listing> known = listings.find(source, l.id());
            Long carId = known.map(Listing::carId).orElse(null);
            Optional<Car> car = carId != null ? cars.findById(carId)
                    : cars.findBySourceUrl(l.url()).or(() -> cars.findBySourceRef(source, l.id()));
            boolean back = car.map(c -> c.status() == CarStatus.REMOVED).orElse(false);
            boolean over = maxKm != null && l.mileageKm() != null && l.mileageKm() > maxKm;
            boolean priced = search.priced(l);
            boolean tooDear = priced && priceLimit.over(l.price(), search.currency(), maxEur);
            if (known.isEmpty() && car.isEmpty() && !priced) {
                unpriced++; // at the source, but nothing to import it with (an auction): not queued
                continue;
            }
            if (car.isEmpty() && over) {
                overMileage++;
            } else if (car.isEmpty() && tooDear) {
                overPrice++;
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
                listings.seen(source, l, brandOf.get(l.id()), maxKm, tooDear);
            } else if (car.isPresent()) {
                listings.insert(source, l, brandOf.get(l.id()), AutoImportListingRepository.IMPORTED,
                        car.get().id());
            } else {
                listings.insert(source, l, brandOf.get(l.id()), over ? AutoImportListingRepository.SKIPPED
                        : tooDear ? AutoImportListingRepository.PRICE_SKIPPED : AutoImportListingRepository.QUEUED,
                        null);
            }
            if (back) {
                cars.restoreRemoved(car.get().id());
            }
            // the source's own model (autowini's menu) names the car's, also for cars imported before or by hand
            if (car.isPresent() && l.model() != null && !l.model().isBlank()
                    && !l.model().trim().equals(car.get().specs().model())
                    && cars.updateModel(car.get().id(), l.model().trim())) {
                renamed++;
            }
        }

        // gone: missing from a make read whole, or not seen for days in a make the site did not show whole
        Instant staleBefore = Instant.now().minus(Duration.ofDays(STALE_DAYS));
        List<Listing> tracked = listings.ofBrands(source, answered);
        List<Listing> missing = tracked.stream().filter(l -> !found.containsKey(l.listingId())).toList();
        List<Listing> toRemove = missing.stream()
                .filter(l -> complete.contains(l.brandCode()) || l.lastSeen().isBefore(staleBefore)).toList();
        long carsToDelete = toRemove.stream().filter(l -> l.carId() != null).count();

        List<String> notes = new ArrayList<>();
        passes.stream().filter(p -> !p.complete())
                .forEach(p -> notes.add(p.name() + ": " + (p.error() == null ? "not read whole" : p.error())
                        + (p.failed() ? " (nothing of it removed)"
                                : " (only listings not seen for " + STALE_DAYS + " days removed)")));
        if (unpriced > 0) {
            notes.add(unpriced + " without a price (auctions): not imported");
        }
        if (overMileage > 0) {
            notes.add(overMileage + " over " + maxKm + " km (not imported)");
        }
        if (renamed > 0) {
            notes.add(renamed + " cars' model renamed after the source's menu");
        }
        if (overPrice > 0) {
            notes.add(overPrice + " over " + maxEur + " EUR FOB (not imported)");
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
        } else {
            Set<String> removing = toRemove.stream().map(Listing::listingId).collect(Collectors.toSet());
            int kept = 0;
            for (Listing l : missing) {
                if (!removing.contains(l.listingId())) {
                    listings.missed(source, l.listingId());
                    continue;
                }
                if (l.carId() != null && !deleteGone(runId, source, l.carId(), false)) {
                    kept++;
                }
                listings.delete(source, l.listingId());
                gone++;
            }
            if (kept > 0) {
                notes.add(kept + " gone from the source but kept (reserved, sold or archived)");
            }
        }
        if (missing.size() > toRemove.size()) {
            notes.add((missing.size() - toRemove.size()) + " not seen this time in makes not read whole (removed after "
                    + STALE_DAYS + " days)");
        }
        // the source's other cars (added by hand, or of makes not imported now)
        // (every car the automatic import knows a listing of, also of makes not read whole this time: those are
        // left for the next run, not searched again here as if added by hand)
        Set<Long> covered = listings.carIds(source);
        ManualCheck manual = checkOtherCars(search, source, covered, dryRun, runId, notes);
        if (manual.stopped() && status.equals("DONE")) {
            status = "STOPPED";
        }

        if (dryRun) {
            notes.add(0, "dry run: nothing was changed; " + carsToDelete + " of the would-be removed are in our catalog");
        }
        return new Result(status, found.size(), newCount, gone, returned + manual.returned(), passes,
                notes.isEmpty() ? null : String.join("; ", notes), manual.checked(), manual.gone());
    }

    private static final SourceFilters NO_FILTERS = new SourceFilters(false, false, List.of());

    record ManualCheck(int checked, int gone, int returned, boolean stopped) {
    }

    /**
     * The source's cars not covered by the import's listings: their make (as the source names it) is searched from
     * the oldest car's year, without the import's filters. A car missing (by its link and its listing id) is
     * deleted. Makes not read whole, or not in the source's list, are left alone.
     */
    ManualCheck checkOtherCars(SourceSearch search, CarSource source, Set<Long> covered, boolean dryRun, Long runId,
                               List<String> notes) {
        List<CheckedCar> others = cars.findForSourceCheck(source).stream()
                .filter(c -> !covered.contains(c.id()))
                .filter(c -> DELETED_WHEN_GONE.contains(c.status()))
                .toList();
        if (others.isEmpty()) {
            return new ManualCheck(0, 0, 0, false);
        }
        // our make -> the source's makes it covers ("Geely" = guazi's "Geely Auto" + "Geely Galaxy": MakeNames)
        Map<String, List<CatalogBrand>> byName = new HashMap<>();
        settings.catalog(source).forEach(b -> byName.computeIfAbsent(
                key(MakeNames.canonical(b.name(), null).make()), k -> new ArrayList<>()).add(b));
        Map<String, List<CheckedCar>> byMake = others.stream()
                .collect(Collectors.groupingBy(c -> key(c.make()), LinkedHashMap::new, Collectors.toList()));

        int checked = 0;
        int returned = 0;
        int unknownMake = 0;
        List<CheckedCar> toRemove = new ArrayList<>();
        for (var group : byMake.entrySet()) {
            List<CatalogBrand> makes = byName.get(group.getKey());
            if (makes == null) {
                unknownMake += group.getValue().size();
                continue;
            }
            int yearFrom = group.getValue().stream().map(CheckedCar::year).filter(java.util.Objects::nonNull)
                    .mapToInt(Integer::intValue).min().orElse(1990);
            Set<String> urls = new HashSet<>();
            Set<String> ids = new HashSet<>();
            String incomplete = null;
            for (CatalogBrand make : makes) {
                BrandPass p = search.list(new Brand(make.code(), make.name(), true, Math.max(yearFrom, 1990),
                        List.of()), NO_FILTERS, l -> {
                            urls.add(l.url());
                            ids.add(l.id().toLowerCase(java.util.Locale.ROOT));
                        });
                if (!p.complete()) {
                    incomplete = make.name() + ": " + (p.error() == null ? "not read whole" : p.error());
                    break;
                }
            }
            if (incomplete != null) {
                notes.add("cars added by hand, " + incomplete + " (nothing of it removed)");
                continue;
            }
            for (CheckedCar c : group.getValue()) {
                checked++;
                boolean there = urls.contains(c.sourceUrl())
                        || (c.sourceRef() != null && ids.contains(c.sourceRef().toLowerCase(java.util.Locale.ROOT)));
                if (there) {
                    if (!dryRun) {
                        listings.manualSeen(c.id());
                    }
                    continue;
                }
                toRemove.add(c);
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
            if (dryRun || deleteGone(runId, source, c.id(), true)) {
                gone++;
            }
        }
        if (checked > 0) {
            notes.add("cars added by hand: " + checked + " checked, " + gone + (dryRun ? " would be" : "")
                    + " removed");
        }
        return new ManualCheck(checked, gone, returned, false);
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s-]+", " ");
    }

    /**
     * Deletes a car whose listing is gone (its photos too) and notes it in the run's report with where it was
     * posted, taken before the delete (the posts go with the car), to take those posts down. A reserved, sold or
     * archived car is kept.
     *
     * @param runId the run, or null (deleted by hand)
     * @return false when the car was kept or could not be deleted
     */
    boolean deleteGone(Long runId, CarSource source, long carId, boolean manual) {
        Optional<Car> car = cars.findById(carId);
        if (car.isEmpty()) {
            return true; // deleted already (one car, two listing rows)
        }
        if (!DELETED_WHEN_GONE.contains(car.get().status())) {
            return false;
        }
        Car c = car.get();
        List<PostedChannels.Post> posts = posted.of(carId);
        try {
            listings.deleteOfCar(carId);
            carService.delete(carId);
        } catch (RuntimeException e) {
            log.warn("Could not delete car {} gone from {}: {}", carId, source, e.toString());
            return false;
        }
        runs.addRemoved(runId, source, null, c.specs().title(), c.slug(), c.sourceUrl(), manual, posts);
        return true;
    }

    /** Deletes the cars an earlier version hid as REMOVED (they were noted when hidden). */
    void purgeRemoved() {
        for (Long id : cars.findRemovedBefore(Instant.now())) {
            try {
                carService.delete(id);
            } catch (RuntimeException e) {
                log.warn("Could not delete removed car {}: {}", id, e.toString());
            }
        }
    }
}
