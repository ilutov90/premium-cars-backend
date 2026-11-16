package com.premiumcars.catalog.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.PricingInputs;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.catalog.repo.PriceRecalcRepository;
import com.premiumcars.catalog.repo.PriceRecalcRepository.Item;
import com.premiumcars.catalog.repo.PriceRecalcRepository.Run;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.common.PageResponse;
import com.premiumcars.importing.ImportService;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.pricing.PriceBreakdown;
import com.premiumcars.pricing.PriceProfile;
import com.premiumcars.pricing.PricingProfile;
import com.premiumcars.pricing.PricingProfileRepository;
import com.premiumcars.social.PostedChannels;

/**
 * Recalculates the price of every car (of one site or of all), only when an admin starts it (admin panel "Цени"):
 * with today's formulas and exchange rates, from the FOB price kept at the import ({@link Mode#STORED}) or from the
 * price the listing shows now ({@link Mode#SOURCE}: each listing is read again, one at a time and a second apart, so
 * thousands of cars take hours). Cars whose final price was set by hand keep it. The report lists the cars whose
 * price changed (and those whose listing could not be read), with where each one was posted, to update the posts.
 * One recalculation at a time, in the background. The cars go in the order of their ids and the run keeps how far it
 * got ({@code last_car_id}), so a run cut by a restart continues after startup where it stopped.
 */
@Service
public class PriceRecalcService {

    /**
     * false on a second server that shares this database only to run the automatic import (premium-cars-infra
     * "local-worker"): production keeps doing this job alone.
     */
    @org.springframework.beans.factory.annotation.Value("${app.background-jobs:true}")
    private boolean backgroundJobs = true;

    private static final Logger log = LoggerFactory.getLogger(PriceRecalcService.class);

    /** Where the FOB price comes from. */
    public enum Mode { STORED, SOURCE }

    /** Sites whose listings can be read again. */
    static final Set<CarSource> READABLE = EnumSet.of(CarSource.AUTOWINI, CarSource.GUAZI, CarSource.AUTOHOME);
    private static final long SOURCE_PAUSE_MS = 1_000;
    private static final int PROGRESS_EVERY = 20;

    private final CarRepository cars;
    private final CarMapper mapper;
    private final PricingProfileRepository profiles;
    private final PriceRecalcRepository runs;
    private final PostedChannels posted;
    private final ImportService importService;
    private final TransactionTemplate tx;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "price-recalc");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean busy;
    private volatile boolean stopping;

    public PriceRecalcService(CarRepository cars, CarMapper mapper, PricingProfileRepository profiles,
                              PriceRecalcRepository runs, PostedChannels posted, ImportService importService,
                              PlatformTransactionManager transactions) {
        this.cars = cars;
        this.mapper = mapper;
        this.profiles = profiles;
        this.runs = runs;
        this.posted = posted;
        this.importService = importService;
        this.tx = new TransactionTemplate(transactions);
    }

    /** The counters of a run so far (a new run starts from zero, a resumed one from what it had). */
    record Counters(int processed, int changed, int fixed, int failed) {

        static final Counters ZERO = new Counters(0, 0, 0, 0);
    }

    /**
     * At startup: a recalculation the restart cut (still RUNNING) continues on the background thread from the car
     * after the last one it did, with its counters and its report so far; one that cannot (started before 041,
     * or its site or mode unknown) is marked FAILED as far as it got, as are older ones (only one runs at a time).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterrupted() {
        if (backgroundJobs) {
            resumeRunning();
        }
    }

    /** The runs marked RUNNING: the newest continues when it can, the others are abandoned (tests call it). */
    public synchronized void resumeRunning() {
        if (busy) {
            return; // a run goes on here: nothing was cut
        }
        boolean resumed = false;
        for (Run r : runs.findRunning()) {
            if (resumed || !resume(r)) {
                runs.abandon(r.id());
                log.info("Price recalculation {} abandoned after the restart: {} of {} cars done", r.id(),
                        r.processed(), r.total());
            } else {
                resumed = true;
            }
        }
    }

    /** Continues a cut run in the background; false when it cannot continue. */
    private boolean resume(Run r) {
        CarSource source;
        Mode mode;
        try {
            source = "ALL".equals(r.scope()) ? null : CarSource.valueOf(r.scope());
            mode = Mode.valueOf(r.mode());
        } catch (IllegalArgumentException e) {
            return false;
        }
        PriceRecalcRepository.Position pos = runs.position(r.id()).orElse(null);
        if (pos != null && !pos.resumable()) {
            // a run from before 041: it continues after the last car it noted (some cars are done twice, none skipped)
            List<Long> all = cars.findIds(source);
            pos = all.isEmpty() ? null : runs.adoptPosition(r.id(), all.getLast()).orElse(null);
        }
        if (pos == null || !pos.resumable()) {
            return false;
        }
        long after = pos.lastCarId() == null ? 0 : pos.lastCarId();
        List<Long> ids = cars.findIds(source, after, pos.endCarId());
        Counters so = new Counters(r.processed(), r.changed(), r.fixedPrice(), r.failed());
        log.info("price recalculation {} continues after car {}: {} of {}", r.id(), after, r.processed(), r.total());
        submit(r.id(), ids, mode, so);
        return true;
    }

    /**
     * Starts a recalculation in the background.
     *
     * @param source the cars of this site, or all cars when null
     */
    public synchronized Run start(CarSource source, Mode mode, String by) {
        if (busy || runs.running()) {
            throw new BadRequestException("A recalculation is running already; wait until it ends");
        }
        List<Long> ids = cars.findIds(source);
        long id = runs.start(source == null ? "ALL" : source.name(), mode.name(), by, ids.size(),
                ids.isEmpty() ? 0 : ids.getLast());
        submit(id, ids, mode, Counters.ZERO);
        log.info("Price recalculation {} started by {}: {} cars of {}, {}", id, by, ids.size(),
                source == null ? "all sites" : source, mode);
        return runs.find(id).orElseThrow();
    }

    private void submit(long id, List<Long> ids, Mode mode, Counters from) {
        busy = true;
        try {
            executor.submit(() -> {
                try {
                    run(id, ids, mode, from);
                } finally {
                    busy = false;
                }
            });
        } catch (RuntimeException e) {
            busy = false;
            throw e;
        }
    }

    public List<Run> recent() {
        return runs.recent(20);
    }

    public PageResponse<Item> items(long runId, String kind, boolean onlyPosted, int page, int size) {
        runs.find(runId).orElseThrow(() -> new NotFoundException("No recalculation " + runId));
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 200);
        String k = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(java.util.Locale.ROOT);
        if (k != null && !k.equals("CHANGED") && !k.equals("FAILED")) {
            throw new BadRequestException("Unknown kind: " + kind);
        }
        return PageResponse.of(runs.items(runId, k, onlyPosted, p, s), p, s,
                runs.countItems(runId, k, onlyPosted), Function.identity());
    }

    public void delete(long runId) {
        if (!runs.delete(runId)) {
            throw new BadRequestException("Recalculation " + runId + " is running or does not exist");
        }
    }

    /**
     * Runs synchronously (the background task; tests call it directly): the cars in the order given (by id), each
     * car's new price, its report line and, with a line, the run's counters and position saved together; the
     * counters and position also every {@value #PROGRESS_EVERY} cars. A cut run continues after the position: the
     * few cars done since it was saved have no report line, so doing them again changes nothing.
     */
    void run(long runId, List<Long> ids, Mode mode, Counters from) {
        Map<PriceProfile, PricingProfile> byId = profiles.findAll().stream()
                .collect(Collectors.toMap(PricingProfile::id, Function.identity()));
        Counters done = from;
        Long last = null;
        int sinceProgress = 0;
        try {
            for (Long carId : ids) {
                if (stopping) {
                    // the server is shutting down: the run stays RUNNING and continues after the restart
                    progress(runId, done, last);
                    log.info("Price recalculation {} stops for the shutdown after car {}: {} done", runId, last,
                            done.processed());
                    return;
                }
                Car c = cars.findById(carId).orElse(null);
                Outcome o = c == null ? null : recalculate(c, mode, byId);
                boolean withItem = o != null && o.item() != null;
                Counters now = new Counters(done.processed() + 1,
                        done.changed() + (withItem && !Objects.equals(scale(o.item().oldPriceEur()),
                                scale(o.item().newPriceEur())) ? 1 : 0),
                        done.fixed() + (o != null && o.fixed() ? 1 : 0),
                        done.failed() + (withItem && o.item().kind().equals("FAILED") ? 1 : 0));
                if (o != null) {
                    tx.executeWithoutResult(status -> {
                        save(c, o);
                        if (withItem) {
                            runs.addItem(runId, o.item());
                            progress(runId, now, carId);
                        }
                    });
                }
                done = now;
                last = carId;
                sinceProgress = withItem ? 0 : sinceProgress + 1;
                if (sinceProgress >= PROGRESS_EVERY) {
                    progress(runId, done, carId);
                    sinceProgress = 0;
                }
            }
            progress(runId, done, last);
            runs.finish(runId, "DONE", null);
            log.info("Price recalculation {}: {} cars, {} changed, {} with a price set by hand, {} not read", runId,
                    done.processed(), done.changed(), done.fixed(), done.failed());
        } catch (RuntimeException e) {
            if (stopping) {
                log.info("Price recalculation {} cut by the shutdown after car {}: continues after the restart",
                        runId, last);
                return;
            }
            log.warn("Price recalculation {} failed", runId, e);
            progress(runId, done, last);
            runs.finish(runId, "FAILED", e.toString());
        }
    }

    /**
     * At shutdown: the run stops after its current car with its position saved (still RUNNING), so it continues
     * after the restart; waits for that car a few seconds.
     */
    @PreDestroy
    void stop() {
        stopping = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                log.info("Price recalculation still busy at shutdown: continues from its last saved car");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void progress(long runId, Counters c, Long lastCarId) {
        runs.progress(runId, c.processed(), c.changed(), c.fixed(), c.failed(), lastCarId);
    }

    private void save(Car c, Outcome o) {
        if (o.newSourcePrice() != null) {
            cars.updateSourcePrice(c.id(), o.newSourcePrice(), o.newSourceCurrency());
        }
        cars.savePrice(c.id(), o.total(), o.breakdown());
    }

    /** What a car's recalculation gives, saved by {@link #save}; {@code newSourcePrice} null = unchanged. */
    private record Outcome(Item item, boolean fixed, BigDecimal newSourcePrice, String newSourceCurrency,
                           BigDecimal total, PriceBreakdown breakdown) {
    }

    /** The car's new price (reading its listing again in SOURCE mode, outside any transaction); saves nothing. */
    private Outcome recalculate(Car c, Mode mode, Map<PriceProfile, PricingProfile> byId) {
        PricingInputs p = c.pricing();
        BigDecimal oldSource = p.sourcePrice();
        BigDecimal newSource = null;
        String newCurrency = null;
        String note = null;
        boolean unread = false;
        if (mode == Mode.SOURCE && c.sourceUrl() != null && READABLE.contains(c.source())) {
            try {
                ImportedCar now = importService.readListing(c.sourceUrl());
                if (now.price() != null && now.price().signum() > 0) {
                    String currency = now.currency() == null ? "EUR" : now.currency();
                    if (oldSource == null || oldSource.compareTo(now.price()) != 0
                            || !currency.equals(p.sourceCurrency())) {
                        p = p.withSourcePrice(now.price(), currency);
                        newSource = now.price();
                        newCurrency = currency;
                    }
                } else {
                    unread = true;
                    note = "The listing shows no price now (sold or an auction?): recalculated from the kept FOB";
                }
            } catch (RuntimeException e) {
                unread = true;
                note = "The listing could not be read (" + e.getMessage() + "): recalculated from the kept FOB";
            }
            pause();
        }
        PricingProfile profile = p.profile() == null ? null : byId.get(p.profile());
        PriceBreakdown b = mapper.breakdown(p, profile);
        BigDecimal total = b == null ? null : b.totalEur();
        boolean fixed = p.fixedTotalEur() != null;
        boolean priceChanged = !Objects.equals(scale(c.totalPriceEur()), scale(total));
        if (!priceChanged && !unread) {
            return new Outcome(null, fixed, newSource, newCurrency, total, b);
        }
        if (priceChanged && note == null && p.sourcePrice() != null && oldSource != null
                && p.sourcePrice().compareTo(oldSource) != 0) {
            note = "The source's price changed";
        }
        Item item = new Item(0, c.id(), unread ? "FAILED" : "CHANGED", c.specs().title(), c.slug(),
                c.status().name(), c.source() == null ? null : c.source().name(), c.sourceUrl(), c.totalPriceEur(),
                total, oldSource, p.sourcePrice(), p.sourceCurrency(), note, new ArrayList<>(posted.of(c.id())));
        return new Outcome(item, fixed, newSource, newCurrency, total, b);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static void pause() {
        try {
            Thread.sleep(SOURCE_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
