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
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

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
 * One recalculation at a time, in the background.
 */
@Service
public class PriceRecalcService {

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
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "price-recalc");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean busy;

    public PriceRecalcService(CarRepository cars, CarMapper mapper, PricingProfileRepository profiles,
                              PriceRecalcRepository runs, PostedChannels posted, ImportService importService) {
        this.cars = cars;
        this.mapper = mapper;
        this.profiles = profiles;
        this.runs = runs;
        this.posted = posted;
        this.importService = importService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void abandonInterrupted() {
        runs.abandonRunning();
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
        long id = runs.start(source == null ? "ALL" : source.name(), mode.name(), by, ids.size());
        busy = true;
        executor.submit(() -> {
            try {
                run(id, ids, mode);
            } finally {
                busy = false;
            }
        });
        log.info("Price recalculation {} started by {}: {} cars of {}, {}", id, by, ids.size(),
                source == null ? "all sites" : source, mode);
        return runs.find(id).orElseThrow();
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

    /** Runs synchronously (the background task; tests call it directly). */
    void run(long runId, List<Long> ids, Mode mode) {
        Map<PriceProfile, PricingProfile> byId = profiles.findAll().stream()
                .collect(Collectors.toMap(PricingProfile::id, Function.identity()));
        int processed = 0;
        int changed = 0;
        int fixed = 0;
        int failed = 0;
        try {
            for (Long carId : ids) {
                Car c = cars.findById(carId).orElse(null);
                if (c != null) {
                    Outcome o = recalculate(c, mode, byId);
                    if (o.fixed()) {
                        fixed++;
                    }
                    if (o.item() != null) {
                        runs.addItem(runId, o.item());
                        if (o.item().kind().equals("FAILED")) {
                            failed++;
                        }
                        if (!Objects.equals(scale(o.item().oldPriceEur()), scale(o.item().newPriceEur()))) {
                            changed++;
                        }
                    }
                }
                processed++;
                if (processed % PROGRESS_EVERY == 0) {
                    runs.progress(runId, processed, changed, fixed, failed);
                }
            }
            runs.progress(runId, processed, changed, fixed, failed);
            runs.finish(runId, "DONE", null);
            log.info("Price recalculation {}: {} cars, {} changed, {} with a price set by hand, {} not read", runId,
                    processed, changed, fixed, failed);
        } catch (RuntimeException e) {
            log.warn("Price recalculation {} failed", runId, e);
            runs.progress(runId, processed, changed, fixed, failed);
            runs.finish(runId, "FAILED", e.toString());
        }
    }

    private record Outcome(Item item, boolean fixed) {
    }

    private Outcome recalculate(Car c, Mode mode, Map<PriceProfile, PricingProfile> byId) {
        PricingInputs p = c.pricing();
        BigDecimal oldSource = p.sourcePrice();
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
                        cars.updateSourcePrice(c.id(), now.price(), currency);
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
        cars.savePrice(c.id(), total, b);
        boolean fixed = p.fixedTotalEur() != null;
        boolean priceChanged = !Objects.equals(scale(c.totalPriceEur()), scale(total));
        if (!priceChanged && !unread) {
            return new Outcome(null, fixed);
        }
        if (priceChanged && note == null && p.sourcePrice() != null && oldSource != null
                && p.sourcePrice().compareTo(oldSource) != 0) {
            note = "The source's price changed";
        }
        Item item = new Item(0, c.id(), unread ? "FAILED" : "CHANGED", c.specs().title(), c.slug(),
                c.status().name(), c.source() == null ? null : c.source().name(), c.sourceUrl(), c.totalPriceEur(),
                total, oldSource, p.sourcePrice(), p.sourceCurrency(), note, new ArrayList<>(posted.of(c.id())));
        return new Outcome(item, fixed);
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
