package com.premiumcars.catalog.service;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.premiumcars.autoimport.AutoImportInstance;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.MakeNames;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.catalog.repo.CarRepository.UnnamedCar;
import com.premiumcars.importing.ImportService;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.importing.SourceBlockedException;

/**
 * Names the autowini cars imported while their pages could not be read (Nov 2026: autowini merged "Make" and
 * "Model / Class / Grade" into one row, so they came in as "Car" without a make). In the background after startup, on
 * the server that runs the automatic import: each such listing is read again and the car gets the title, make and
 * trim of the page; its model stays (the automatic import's menu model) unless it has none. Published cars keep their
 * link; drafts get a new one. Nothing is left to do on later starts. Off with
 * {@code app.importer.repair-autowini-names=false} (tests).
 */
@Component
public class AutowiniNameRepair {

    private static final Logger log = LoggerFactory.getLogger(AutowiniNameRepair.class);

    private final CarRepository cars;
    private final ImportService importService;
    private final AutoImportInstance instance;
    private final boolean enabled;

    public AutowiniNameRepair(CarRepository cars, ImportService importService, AutoImportInstance instance,
                              @Value("${app.importer.repair-autowini-names:true}") boolean enabled) {
        this.cars = cars;
        this.importService = importService;
        this.instance = instance;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (enabled && instance.enabled()) {
            Thread.ofVirtual().name("autowini-name-repair").start(this::run);
        }
    }

    void run() {
        List<UnnamedCar> unnamed;
        try {
            unnamed = cars.findUnnamed(CarSource.AUTOWINI);
        } catch (RuntimeException e) {
            log.warn("autowini name repair: could not list the cars", e);
            return;
        }
        if (unnamed.isEmpty()) {
            return;
        }
        log.info("autowini name repair: {} cars named \"Car\" without a make", unnamed.size());
        int named = 0;
        int failed = 0;
        for (UnnamedCar car : unnamed) {
            try {
                if (repair(car)) {
                    named++;
                }
            } catch (SourceBlockedException e) {
                log.warn("autowini name repair stopped after {} cars: {}; the rest on the next start", named,
                        e.getMessage());
                return;
            } catch (RuntimeException e) {
                failed++;
                log.info("autowini name repair: car {} ({}) not named: {}", car.id(), car.sourceUrl(),
                        e.getMessage());
            }
            if (!pause(instance.queuePause())) {
                return;
            }
        }
        log.info("autowini name repair done: {} named, {} not (listing gone or unreadable)", named, failed);
    }

    private boolean repair(UnnamedCar car) {
        ImportedCar listing = importService.readListing(car.sourceUrl());
        CarSpecs read = listing.specs();
        String model = car.model() != null && !car.model().isBlank() ? car.model() : read.model();
        MakeNames.Name name = MakeNames.canonical(read.make(), model);
        if (!cars.updateNames(car.id(), read.title(), name.make(), name.model(), read.trim())) {
            return false;
        }
        if (car.status() == CarStatus.DRAFT) {
            cars.updateSlug(car.id(), Slugs.forCar(name.make(), name.model(), read.title(), read.year(), car.id()));
        }
        return true;
    }

    private static boolean pause(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(d);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
