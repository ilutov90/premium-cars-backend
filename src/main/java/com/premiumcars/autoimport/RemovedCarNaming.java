package com.premiumcars.autoimport;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.premiumcars.autoimport.AutoImportRunRepository.UnnamedRemoved;
import com.premiumcars.catalog.domain.CarSource;

/**
 * Names the deleted cars noted before 039 ({@code auto_import_removed} rows without a make), once, in the background
 * after startup: their cars are gone, so only the title and slug are left ({@link RemovedCarNames#fromText} against
 * the source's lists). A row it cannot name keeps its make null (and is looked at again on the next start, which
 * changes nothing until the source's lists do). Nothing to do: nothing happens. Off on a second server that shares
 * the database ({@code app.background-jobs=false}) and with {@code app.auto-import.name-removed-cars=false} (tests).
 */
@Component
@ConditionalOnProperty(name = "app.auto-import.name-removed-cars", havingValue = "true", matchIfMissing = true)
public class RemovedCarNaming {

    private static final Logger log = LoggerFactory.getLogger(RemovedCarNaming.class);
    static final int BATCH = 200;

    @org.springframework.beans.factory.annotation.Value("${app.background-jobs:true}")
    private boolean backgroundJobs = true;

    private final AutoImportRunRepository runs;
    private final AutoImportSettingsRepository settings;

    public RemovedCarNaming(AutoImportRunRepository runs, AutoImportSettingsRepository settings) {
        this.runs = runs;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!backgroundJobs) {
            return;
        }
        Thread.ofVirtual().name("removed-car-naming").start(this::run);
    }

    /** @return how many rows got a make */
    int run() {
        Map<CarSource, List<RemovedCarNames.Make>> makes = new EnumMap<>(CarSource.class);
        long after = 0;
        int seen = 0;
        int named = 0;
        int dated = 0;
        try {
            while (true) {
                List<UnnamedRemoved> batch = runs.unnamedRemoved(after, BATCH);
                if (batch.isEmpty()) {
                    break;
                }
                for (UnnamedRemoved row : batch) {
                    after = row.id();
                    seen++;
                    List<RemovedCarNames.Make> list = makes.computeIfAbsent(row.source(),
                            s -> RemovedCarNames.makes(settings.catalog(s), settings.brands(s)));
                    RemovedCarNames.Names n = RemovedCarNames.fromText(row.title(), row.slug(), list);
                    if (n.make() == null && n.year() == null) {
                        continue;
                    }
                    if (runs.nameRemoved(row.id(), n.make(), n.model(), n.year())) {
                        if (n.make() != null) {
                            named++;
                        } else {
                            dated++;
                        }
                    }
                }
                if (batch.size() < BATCH) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Naming the deleted cars stopped after {} rows: {}", seen, e.toString());
            return named;
        }
        if (seen > 0) {
            log.info("Deleted cars without a make: {} looked at, {} named, {} only dated, {} left unnamed", seen,
                    named, dated, seen - named);
        }
        return named;
    }
}
