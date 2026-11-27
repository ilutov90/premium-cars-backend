package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

import com.premiumcars.autoimport.AutoImportListingRepository.CarListing;
import com.premiumcars.autoimport.AutoImportListingRepository.Listing;
import com.premiumcars.autoimport.AutoImportRunRepository.RemovedFacts;
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
import com.premiumcars.catalog.repo.PriceRecalcRepository;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.service.PriceRecalcService;
import com.premiumcars.pricing.PriceBreakdown;
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
 *       take those posts down.</li>
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
            java.util.EnumSet.of(CarStatus.PUBLISHED, CarStatus.DRAFT);

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

    private final PriceRecalcService priceRecalc;

    public AutoImportRunner(List<SourceSearch> searches, AutoImportSettingsRepository settings,
                            AutoImportListingRepository listings, AutoImportRunRepository runs, CarRepository cars,
                            CarService carService, AutoImportMailer mailer, PostedChannels posted,
                            PriceLimit priceLimit, AutoImportInstance instance, PriceRecalcService priceRecalc) {
        this.instance = instance;
        this.priceRecalc = priceRecalc;
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

    private BrandPass readBrand(SourceSearch search, Brand brand, SourceSettings s, Map<String, FoundListing> found,
                                Map<String, String> brandOf) {
        return search.list(brand, s.filters(), l -> {
            if (found.putIfAbsent(l.id(), l) == null) {
                brandOf.put(l.id(), brand.code());
            }
        });
    }

    private static boolean refusedSince(SourceSearch search, Instant started) {
        Instant refused = search.lastRefused();
        return refused != null && !refused.isBefore(started);
    }

    private boolean importOn(CarSource source) {
        return settings.enabled() && settings.source(source).map(SourceSettings::enabled).orElse(false);
    }

    /** Sleeps in slices of at most 30 s; false when the import was switched off or the thread interrupted. */
    private boolean waitWhileOn(CarSource source, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        try {
            long left;
            while ((left = end - System.nanoTime()) > 0) {
                Thread.sleep(Math.min(30_000L, Math.max(1L, left / 1_000_000L)));
                if (!importOn(source)) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
        List<Duration> waited = new ArrayList<>();
        boolean stillRefused = false;
        for (Brand brand : brands) {
            Instant started = Instant.now();
            BrandPass p = readBrand(search, brand, s, found, brandOf);
            while (p.failed() && !dryRun && waited.size() < instance.refusedWaits().size()
                    && refusedSince(search, started) && importOn(source)) {
                Duration wait = instance.refusedWaits().get(waited.size());
                log.warn("Auto-import {}: the site refused {}; waiting {} before reading it again", source,
                        brand.name(), wait);
                if (!waitWhileOn(source, wait)) {
                    break;
                }
                waited.add(wait);
                started = Instant.now();
                p = readBrand(search, brand, s, found, brandOf);
                stillRefused = p.failed() && refusedSince(search, started);
            }
            passes.add(p);
        }
        Set<String> complete = passes.stream().filter(BrandPass::complete).map(BrandPass::code)
                .collect(Collectors.toSet());
        Set<String> answered = passes.stream().filter(p -> !p.failed()).map(BrandPass::code)
                .collect(Collectors.toSet());

        // new, seen again
        Integer maxKm = settings.maxMileageKm();
        Integer maxEur = priceLimit.maxEur();
        int newCount = 0;
        int overMileage = 0;
        int overPrice = 0;
        int unpriced = 0;
        int renamed = 0;
        List<PriceMove> moves = new ArrayList<>();
        for (FoundListing l : found.values()) {
            Optional<Listing> known = listings.find(source, l.id());
            Long carId = known.map(Listing::carId).orElse(null);
            Optional<Car> car = carId != null ? cars.findById(carId)
                    : cars.findBySourceUrl(l.url()).or(() -> cars.findBySourceRef(source, l.id()));
            if (car.isPresent() && known.isPresent()) {
                PriceMove m = PriceMove.of(l.id(), known.get().price(), l.price());
                if (m != null) {
                    moves.add(m);
                }
            }
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
        if (!waited.isEmpty()) {
            notes.add("the site asked for a bot check: waited "
                    + waited.stream().map(d -> d.toMinutes() + " min").collect(Collectors.joining(", "))
                    + (stillRefused ? " and it still refused" : " and read on"));
        }
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
                notes.add(kept + " gone from the source but kept (reserved, sold, archived or with an order)");
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
        ManualCheck manual = checkOtherCars(search, source, s.filters(), covered, dryRun, runId, notes);
        if (manual.stopped() && status.equals("DONE")) {
            status = "STOPPED";
        }

        // the linked cars whose price moved in the search: their listing read again and their price recalculated
        Set<String> moved = movedPrices(moves);
        if (dryRun) {
            if (!moved.isEmpty()) {
                notes.add(moved.size() + " cars with a changed price (their prices would be recalculated)");
            }
        } else {
            listings.markReprice(source, moved);
            int waiting = listings.repriceCarIds(source).size();
            if (waiting > 0) {
                repricePending.add(source);
                notes.add(moved.size() + " cars with a changed price tonight (" + waiting + " to recalculate): their"
                        + " prices are recalculated once the new cars are imported (\"Цени\" report)");
            }
        }

        if (dryRun) {
            notes.add(0, "dry run: nothing was changed; " + carsToDelete + " of the would-be removed are in our catalog");
        }
        return new Result(status, found.size(), newCount, gone, 0, passes,
                notes.isEmpty() ? null : String.join("; ", notes), manual.checked(), manual.gone());
    }

    record ManualCheck(int checked, int gone, int returned, boolean stopped) {
    }

    /** A linked car's search price: the one the last run saw and tonight's (both positive). */
    record PriceMove(String listingId, BigDecimal before, BigDecimal now) {

        static PriceMove of(String listingId, BigDecimal before, BigDecimal now) {
            return before == null || now == null || before.signum() <= 0 || now.signum() <= 0 ? null
                    : new PriceMove(listingId, before, now);
        }

        double ratio() {
            return now.doubleValue() / before.doubleValue();
        }
    }

    /** A price that moved by more than this share, besides the run's typical move, changed. */
    static final double PRICE_MOVE = 0.005;
    /** The run's typical move counts from this many linked listings on. */
    static final int TYPICAL_MOVE_FROM = 10;

    /**
     * The listings whose price changed: moved by more than {@value #PRICE_MOVE} besides the typical move of all the
     * run's linked listings (their median ratio). autowini's search prices follow an exchange rate and all move a
     * little from day to day; such a move alone changes no car.
     */
    static Set<String> movedPrices(List<PriceMove> moves) {
        if (moves.isEmpty()) {
            return Set.of();
        }
        double[] ratios = moves.stream().mapToDouble(PriceMove::ratio).sorted().toArray();
        int n = ratios.length;
        // a few listings tell no typical move: their own move counts
        double median = n < TYPICAL_MOVE_FROM ? 1
                : n % 2 == 1 ? ratios[n / 2] : (ratios[n / 2 - 1] + ratios[n / 2]) / 2;
        Set<String> out = new LinkedHashSet<>();
        for (PriceMove m : moves) {
            if (Math.abs(m.ratio() / median - 1) > PRICE_MOVE) {
                out.add(m.listingId());
            }
        }
        return out;
    }

    /** The sources whose run left cars to recalculate, for their import queue once it is empty. */
    private final Set<CarSource> repricePending = ConcurrentHashMap.newKeySet();

    /**
     * Called by the source's import queue once it is empty: after a run that left cars with a changed price, those
     * cars (tonight's and those a run before could not do) are recalculated now, on the queue's thread; at most once
     * per run, and it stops when the source's next run starts.
     */
    public void recalculateChangedAfterQueue(CarSource source) {
        if (!repricePending.remove(source)) {
            return;
        }
        List<Long> ids = listings.repriceCarIds(source);
        if (ids.isEmpty()) {
            return;
        }
        try {
            PriceRecalcRepository.Run r = priceRecalc.recalculateChanged(source, ids, "auto-import",
                    carId -> listings.clearReprice(source, carId), () -> !running(source));
            if (r == null) {
                log.info("Auto-import {}: {} cars with a changed price wait: an admin's price recalculation runs",
                        source, ids.size());
            } else {
                log.info("Auto-import {}: price recalculation {} of the cars with a changed price: {} of {}, {} new"
                        + " prices", source, r.id(), r.processed(), ids.size(), r.changed());
            }
        } catch (RuntimeException e) {
            log.warn("Auto-import {}: recalculating the changed prices failed", source, e);
        }
    }

    /**
     * The source's cars not covered by the import's listings: each looked up by its listing id where the site can
     * ({@link SourceSearch#listed}: autowini), otherwise their make (as the source names it) is searched from the
     * oldest car's year, without the import's filters. A car missing (by its link and its listing id) is deleted.
     * Cars the site did not answer for, makes not read whole, or not in the source's list, are left alone.
     */
    ManualCheck checkOtherCars(SourceSearch search, CarSource source, Set<Long> covered, boolean dryRun, Long runId,
                               List<String> notes) {
        return checkOtherCars(search, source, null, covered, dryRun, runId, notes);
    }

    /**
     * As above; of the source's own filters only autowini's location applies (a car located outside S.Korea counts
     * as gone when {@code koreaOnly}), never the others.
     */
    ManualCheck checkOtherCars(SourceSearch search, CarSource source, SourceFilters own, Set<Long> covered,
                               boolean dryRun, Long runId, List<String> notes) {
        SourceFilters location = new SourceFilters(false, false, List.of(), null,
                own != null && Boolean.TRUE.equals(own.koreaOnly()));
        Set<Long> ordered = carService.orderedCarIds();
        List<CheckedCar> others = cars.findForSourceCheck(source).stream()
                .filter(c -> !covered.contains(c.id()))
                .filter(c -> DELETED_WHEN_GONE.contains(c.status()))
                .filter(c -> !ordered.contains(c.id()))
                .toList();
        if (others.isEmpty()) {
            return new ManualCheck(0, 0, 0, false);
        }
        // our make -> the source's makes it covers ("Geely" = guazi's "Geely Auto" + "Geely Galaxy": MakeNames)
        Map<String, List<CatalogBrand>> byName = new HashMap<>();
        settings.catalog(source).forEach(b -> byName.computeIfAbsent(
                key(MakeNames.canonical(b.name(), null).make()), k -> new ArrayList<>()).add(b));
        int checked = 0;
        int returned = 0;
        int unknownMake = 0;
        List<CheckedCar> toRemove = new ArrayList<>();

        // one request per car where the site looks up a listing by its id (autowini); the others by their make
        List<CheckedCar> byMakeCars = new ArrayList<>();
        boolean oneByOne = true;
        int unanswered = 0;
        for (CheckedCar c : others) {
            Boolean there = null;
            if (oneByOne && c.sourceRef() != null && !c.sourceRef().isBlank()) {
                try {
                    there = search.listed(c.sourceRef().trim(), location);
                } catch (SourceSearch.SearchException e) {
                    unanswered++;
                    continue;
                }
                oneByOne = there != null;
            }
            if (there == null) {
                byMakeCars.add(c);
                continue;
            }
            checked++;
            if (there) {
                if (!dryRun) {
                    listings.manualSeen(c.id());
                }
            } else {
                toRemove.add(c);
            }
        }
        if (unanswered > 0) {
            notes.add("cars added by hand: " + unanswered + " not answered by the site (nothing of them removed)");
        }
        Map<String, List<CheckedCar>> byMake = byMakeCars.stream()
                .collect(Collectors.groupingBy(c -> key(c.make()), LinkedHashMap::new, Collectors.toList()));
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
                        List.of()), location, l -> {
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
     * archived car is kept, and so is a car with a customer order (in any status).
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
        if (carService.hasOrder(carId)) {
            log.info("Car {} gone from {} has a customer order: kept", carId, source);
            return false;
        }
        Car c = car.get();
        List<PostedChannels.Post> posts = posted.of(carId);
        RemovedFacts facts = facts(source, c);
        // a car checked on its own (by its listing id, or its make searched without filters) is really gone
        boolean outOfFilters = !manual && outOfFilters(source, c, facts);
        try {
            listings.deleteOfCar(carId);
            if (!carService.deleteUnlessOrdered(carId)) {
                return false;
            }
        } catch (RuntimeException e) {
            log.warn("Could not delete car {} gone from {}: {}", carId, source, e.toString());
            return false;
        }
        runs.addRemoved(runId, source, null, c.specs().title(), c.slug(), c.sourceUrl(), manual, posts, facts,
                outOfFilters);
        return true;
    }

    /**
     * The car about to be deleted is outside the import's current filters ({@link RemovedFilterCheck}): its listing
     * was not looked for, so it is not counted as sold. Read before its listing rows go; never keeps the car from
     * being deleted (false when anything fails).
     */
    boolean outOfFilters(CarSource source, Car c, RemovedFacts facts) {
        try {
            List<CarListing> rows = listings.ofCar(c.id());
            CarListing row = rows.stream().filter(r -> r.brandCode() != null).findFirst()
                    .orElse(rows.isEmpty() ? null : rows.getFirst());
            List<Brand> brands = settings.brands(source);
            String brandCode = row == null ? null : row.brandCode();
            String make = facts == null ? null : facts.make();
            List<String> menu = RemovedCarNames.makes(settings.catalog(source), brands).stream()
                    .filter(m -> brandCode != null ? brandCode.equals(m.code())
                            : make != null && make.equalsIgnoreCase(m.name()))
                    .findFirst().map(RemovedCarNames.Make::models).orElse(List.of());
            var specs = c.specs();
            RemovedFilterCheck.CarFacts car = new RemovedFilterCheck.CarFacts(brandCode, make,
                    row == null ? null : row.model(), facts == null ? specs.model() : facts.model(),
                    facts != null && facts.year() != null ? facts.year() : specs.year(), specs.fuelType(),
                    specs.inspection() == null ? null : specs.inspection().grade());
            SourceFilters filters = settings.source(source).map(SourceSettings::filters).orElse(null);
            return RemovedFilterCheck.outOfFilters(source, brands, filters, menu, car);
        } catch (RuntimeException e) {
            log.warn("Could not tell whether car {} of {} was out of the filters: {}", c.id(), source, e.toString());
            return false;
        }
    }

    /**
     * What a car about to be deleted was, for the statistics (read before its listing rows go): the make and model
     * by the source's menus ({@link RemovedCarNames#fromCar}), its specs, FOB and final price, and when its listing
     * was first found (else when the car was created). Never keeps the car from being deleted.
     */
    RemovedFacts facts(CarSource source, Car c) {
        try {
            List<CarListing> rows = listings.ofCar(c.id());
            CarListing row = rows.stream().filter(r -> r.brandCode() != null).findFirst()
                    .orElse(rows.isEmpty() ? null : rows.getFirst());
            List<RemovedCarNames.Make> makes = RemovedCarNames.makes(settings.catalog(source),
                    settings.brands(source));
            var specs = c.specs();
            RemovedCarNames.Names names = RemovedCarNames.fromCar(row == null ? null : row.brandCode(),
                    row == null ? null : row.model(), specs.make(), specs.model(), specs.year(), specs.title(),
                    c.slug(), makes);
            BigDecimal fob = cars.findPriceBreakdown(c.id()).map(PriceBreakdown::carPriceEur).orElse(null);
            Instant firstSeen = rows.stream().map(CarListing::firstSeen).min(Instant::compareTo)
                    .orElse(c.createdAt());
            return new RemovedFacts(names.make(), names.model(), names.year(),
                    specs.fuelType() == null ? null : specs.fuelType().name(), specs.mileageKm(), fob,
                    c.totalPriceEur(), firstSeen);
        } catch (RuntimeException e) {
            log.warn("Could not read what car {} of {} was: {}", c.id(), source, e.toString());
            return new RemovedFacts(null, null, null, null, null, null, c.totalPriceEur(), c.createdAt());
        }
    }
}
