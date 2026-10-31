package com.premiumcars.autoimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.premiumcars.autoimport.AutoImportDtos.BrandUpdate;
import com.premiumcars.autoimport.AutoImportDtos.SourceUpdate;
import com.premiumcars.autoimport.AutoImportRunRepository.Result;
import com.premiumcars.autoimport.AutoImportSettingsRepository.SourceSettings;
import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.BrandPass;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.social.PostedChannels;

/**
 * The run's bookkeeping against PostgreSQL 17 in Docker, with a made-up search instead of the sites: new listings
 * queued, cars imported by hand linked, removal after two missed runs, return, the 20 % guard, incomplete makes.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AutoImportRunnerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    AutoImportRunner runner;

    @Autowired
    AutoImportService service;

    @Autowired
    AutoImportSettingsRepository settings;

    @Autowired
    AutoImportListingRepository listings;

    @Autowired
    CarService carService;

    @Autowired
    AutoImportWorker worker;

    @Autowired
    AutoImportRunRepository runs;

    @Autowired
    com.premiumcars.mobilebg.MobileBgRepository mobileBg;

    /** Answers per make code; a make not in the map has no listings (read whole). */
    static class FakeSearch implements SourceSearch {
        final Map<String, List<FoundListing>> results = new HashMap<>();
        final Map<String, Boolean> complete = new HashMap<>();

        @Override
        public CarSource source() {
            return CarSource.GUAZI;
        }

        @Override
        public List<CatalogBrand> brands() {
            return List.of();
        }

        @Override
        public List<CatalogModel> models(String brandCode) {
            return List.of();
        }

        @Override
        public int count(Brand brand, SourceFilters filters) {
            return results.getOrDefault(brand.code(), List.of()).size();
        }

        @Override
        public BrandPass list(Brand brand, SourceFilters filters, Consumer<FoundListing> found) {
            List<FoundListing> list = results.getOrDefault(brand.code(), List.of());
            list.forEach(found);
            boolean whole = complete.getOrDefault(brand.code(), true);
            return new BrandPass(brand.code(), brand.name(), list.size(), list.size(), whole, whole ? null : "cut");
        }
    }

    private static FoundListing listing(String id) {
        return listing(id, null);
    }

    private static FoundListing listing(String id, Integer km) {
        return new FoundListing(id, "https://en.guazi.com/products/test-car-" + id + ".html", "BYD Seal " + id,
                new BigDecimal("15000"), km);
    }

    private static final SourceSettings GUAZI = new SourceSettings(CarSource.GUAZI, true,
            new SourceFilters(false, false, List.of("S", "A", "B")), Instant.now());

    @Test
    void runsKeepTheCatalogInStepWithTheSource() {
        // the seeded makes; BYD is 1212, Kia 1292
        assertThat(settings.brands(CarSource.GUAZI)).extracting(Brand::code).contains("1212", "1292", "104012");
        assertThat(settings.enabled()).isFalse(); // off until an admin turns it on

        // a car imported by hand earlier, published
        var manual = carService.createDraft(new ImportedCar("aaa1", null, CarSpecs.builder().title("BYD Seal").build(),
                new BigDecimal("15000"), "USD", List.of(), List.of()), CarSource.GUAZI, listing("aaa1").url());
        carService.changeStatus(manual.id(), CarStatus.PUBLISHED);

        FakeSearch search = new FakeSearch();
        search.results.put("1212", List.of(listing("aaa1"), listing("bbb2")));

        // dry run: counts only
        Result dry = runner.pass(search, GUAZI, true);
        assertThat(dry.found()).isEqualTo(2);
        assertThat(dry.newCount()).isEqualTo(1);
        assertThat(listings.count(CarSource.GUAZI)).isZero();

        // real run: the new one queued, the one imported by hand linked
        Result first = runner.pass(search, GUAZI, false);
        assertThat(first.status()).isEqualTo("DONE");
        assertThat(first.newCount()).isEqualTo(1);
        assertThat(listings.find(CarSource.GUAZI, "aaa1").orElseThrow())
                .satisfies(l -> assertThat(l.state()).isEqualTo("IMPORTED"))
                .satisfies(l -> assertThat(l.carId()).isEqualTo(manual.id()));
        assertThat(listings.find(CarSource.GUAZI, "bbb2").orElseThrow().state()).isEqualTo("QUEUED");
        assertThat(listings.nextQueued(List.of(CarSource.GUAZI, CarSource.AUTOWINI)).orElseThrow().listingId())
                .isEqualTo("bbb2");

        // aaa1 gone once: kept; gone twice: hidden
        search.results.put("1212", List.of(listing("bbb2")));
        Result once = runner.pass(search, GUAZI, false);
        assertThat(once.goneCount()).isZero();
        assertThat(carService.getAdmin(manual.id()).status()).isEqualTo(CarStatus.PUBLISHED);
        Result twice = runner.pass(search, GUAZI, false);
        assertThat(twice.goneCount()).isEqualTo(1);
        assertThat(carService.getAdmin(manual.id()).status()).isEqualTo(CarStatus.REMOVED);
        assertThat(listings.find(CarSource.GUAZI, "aaa1")).isEmpty();

        // back at the source: published again
        search.results.put("1212", List.of(listing("aaa1"), listing("bbb2")));
        Result back = runner.pass(search, GUAZI, false);
        assertThat(back.returnedCount()).isEqualTo(1);
        assertThat(back.newCount()).isZero();
        assertThat(carService.getAdmin(manual.id()).status()).isEqualTo(CarStatus.PUBLISHED);
        assertThat(listings.find(CarSource.GUAZI, "aaa1").orElseThrow().carId()).isEqualTo(manual.id());

        // a make not read whole: nothing of it counts as gone
        search.results.put("1212", List.of());
        search.complete.put("1212", false);
        runner.pass(search, GUAZI, false);
        Result cut = runner.pass(search, GUAZI, false);
        assertThat(cut.status()).isEqualTo("INCOMPLETE");
        assertThat(cut.goneCount()).isZero();
        assertThat(carService.getAdmin(manual.id()).status()).isEqualTo(CarStatus.PUBLISHED);
        search.complete.remove("1212");

        // the guard: most listings vanish at once -> nothing removed
        List<FoundListing> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(listing("g" + i));
        }
        search.results.put("1292", many);
        search.results.put("1212", List.of(listing("aaa1"), listing("bbb2")));
        runner.pass(search, GUAZI, false);
        search.results.remove("1292");
        runner.pass(search, GUAZI, false); // missed once
        Result stopped = runner.pass(search, GUAZI, false);
        assertThat(stopped.status()).isEqualTo("STOPPED");
        assertThat(stopped.goneCount()).isZero();
        assertThat(listings.find(CarSource.GUAZI, "g0")).isPresent();
    }

    @Test
    void listingsOverTheMileageLimitAreSkippedUntilTheLimitAllowsThem() {
        assertThat(settings.maxMileageKm()).isEqualTo(200_000); // the default
        service.save(CarSource.AUTOWINI, new SourceUpdate(true, new SourceFilters(false, false, List.of()),
                List.of(new BrandUpdate("C9990", "Testmake", true, 2018, List.of()))));
        var autowini = new SourceSettings(CarSource.AUTOWINI, true, new SourceFilters(false, false, List.of()),
                Instant.now());
        FakeSearch search = new FakeSearch();
        search.results.put("C9990", List.of(listing("km1", 250_000), listing("km2", 90_000), listing("km3", null)));

        Result first = runner.pass(search, autowini, false);
        assertThat(first.newCount()).isEqualTo(2);
        assertThat(first.message()).contains("1 over 200000 km");
        assertThat(state("km1")).isEqualTo("SKIPPED");
        assertThat(state("km2")).isEqualTo("QUEUED");
        assertThat(state("km3")).isEqualTo("QUEUED"); // not shown by the search: the import checks it

        // a higher limit queues it, a lower one skips it again
        settings.setMaxMileageKm(300_000);
        runner.pass(search, autowini, false);
        assertThat(state("km1")).isEqualTo("QUEUED");
        settings.setMaxMileageKm(null);
        assertThat(service.overview().maxMileageKm()).isNull();
        settings.setMaxMileageKm(100_000);
        runner.pass(search, autowini, false);
        assertThat(state("km1")).isEqualTo("SKIPPED");
        assertThat(state("km2")).isEqualTo("QUEUED");

        // the worker skips a queued listing over a limit lowered since the run, without importing it
        settings.setMaxMileageKm(50_000);
        worker.importOne(listings.find(CarSource.AUTOWINI, "km2").orElseThrow());
        assertThat(state("km2")).isEqualTo("SKIPPED");
        assertThat(service.failures(CarSource.AUTOWINI, "km", 0, 50).page().items()).isEmpty(); // skipped, not failed
        settings.setMaxMileageKm(200_000);
    }

    @Test
    void carsAddedByHandAreCheckedByTheirMakeAndHiddenWhenGoneTwice() {
        settings.saveCatalog(CarSource.GUAZI, List.of(new CatalogBrand("777", "Testmake", null)));
        String url = "https://en.guazi.com/products/test-car-hand1.html";
        var car = carService.createDraft(new ImportedCar("hand1", null, CarSpecs.builder().title("Testmake X 2023")
                .make("Testmake").year(2023).build(), new BigDecimal("15000"), "USD", List.of(), List.of()),
                CarSource.GUAZI, url);
        carService.changeStatus(car.id(), CarStatus.PUBLISHED);
        mobileBg.saveListing(car.id(), "99", "https://www.mobile.bg/obiava-99", Instant.now(), "admin");

        FakeSearch search = new FakeSearch();
        search.results.put("777", List.of(listing("hand1"))); // the make is not imported: only the check reads it
        Result present = runner.pass(search, GUAZI, false);
        assertThat(present.manualChecked()).isGreaterThanOrEqualTo(1);
        assertThat(present.manualGone()).isZero();
        assertThat(listings.find(CarSource.GUAZI, "hand1")).isEmpty(); // not queued: it is in the catalog

        search.results.put("777", List.of());
        assertThat(runner.pass(search, GUAZI, true).manualGone()).isZero(); // a dry run: missing once so far
        runner.pass(search, GUAZI, false); // missing once
        assertThat(carService.getAdmin(car.id()).status()).isEqualTo(CarStatus.PUBLISHED);
        long runId = runs.start(CarSource.GUAZI, false);
        Result gone = runner.pass(search, GUAZI, false, runId); // missing twice
        assertThat(gone.manualGone()).isEqualTo(1);
        assertThat(carService.getAdmin(car.id()).status()).isEqualTo(CarStatus.REMOVED);
        assertThat(runs.removed(runId)).singleElement()
                .satisfies(r -> assertThat(r.manual()).isTrue())
                .satisfies(r -> assertThat(r.title()).isEqualTo("Testmake X 2023"))
                .satisfies(r -> assertThat(r.posted()).extracting(PostedChannels.Post::network)
                        .containsExactly("MOBILE_BG"));

        // back at the source: published again
        search.results.put("777", List.of(listing("hand1")));
        runner.pass(search, GUAZI, false);
        assertThat(carService.getAdmin(car.id()).status()).isEqualTo(CarStatus.PUBLISHED);
        carService.delete(car.id());
    }

    @Test
    void failuresAreTriedThreeTimesPerRunAndListedWhole() {
        var autowini = new SourceSettings(CarSource.AUTOWINI, true, new SourceFilters(false, false, List.of()),
                Instant.now());
        service.save(CarSource.AUTOWINI, new SourceUpdate(true, new SourceFilters(false, false, List.of()),
                List.of(new BrandUpdate("C9991", "Failmake", true, 2018, List.of()))));
        FakeSearch search = new FakeSearch();
        search.results.put("C9991", List.of(listing("f1"), listing("f2")));
        runner.pass(search, autowini, false);
        for (int i = 0; i < 3; i++) {
            listings.failed(CarSource.AUTOWINI, "f1", "autowini returned HTTP 404 for https://x/" + i);
            listings.failed(CarSource.AUTOWINI, "f2", "autowini returned HTTP 404 for https://y/" + i);
        }
        assertThat(state("f1")).isEqualTo("FAILED");
        var tab = service.failures(CarSource.AUTOWINI, null, 0, 1);
        assertThat(tab.page().totalItems()).isEqualTo(2);
        assertThat(tab.page().items()).hasSize(1);
        assertThat(tab.reasons()).singleElement()
                .satisfies(r -> assertThat(r.reason()).isEqualTo("autowini returned HTTP N for …"))
                .satisfies(r -> assertThat(r.count()).isEqualTo(2));

        // the next run: three new attempts while the source has it
        runner.pass(search, autowini, false);
        assertThat(state("f1")).isEqualTo("QUEUED");
        assertThat(listings.find(CarSource.AUTOWINI, "f1").orElseThrow().attempts()).isZero();

        for (int i = 0; i < 3; i++) {
            listings.failed(CarSource.AUTOWINI, "f1", "boom");
            listings.failed(CarSource.AUTOWINI, "f2", "boom");
        }
        assertThat(service.deleteFailures(new AutoImportDtos.FailureDelete(
                List.of(new AutoImportDtos.FailureRef(CarSource.AUTOWINI, "f1")), false, null, null))).isEqualTo(1);
        assertThat(service.deleteFailures(new AutoImportDtos.FailureDelete(null, true, CarSource.AUTOWINI, "boom")))
                .isEqualTo(1);
        assertThat(service.failures(CarSource.AUTOWINI, null, 0, 50).page().totalItems()).isZero();
    }

    private String state(String listingId) {
        return listings.find(CarSource.AUTOWINI, listingId).orElseThrow().state();
    }

    @Test
    void settingsAreSavedWhole() {
        var update = new SourceUpdate(true, new SourceFilters(true, true, List.of()), List.of(
                new BrandUpdate("C0810", "Kia", true, 2019, List.of(new AutoImportDtos.ModelRef("C1150", "Carnival"))),
                new BrandUpdate("C0120", "BMW", false, 2016, List.of())));
        var overview = service.save(CarSource.AUTOWINI, update);
        var autowini = overview.sources().stream().filter(s -> s.source() == CarSource.AUTOWINI).findFirst()
                .orElseThrow();
        assertThat(autowini.enabled()).isTrue();
        assertThat(autowini.brands()).hasSize(2);
        assertThat(autowini.brands()).filteredOn(b -> b.code().equals("C0810")).singleElement()
                .satisfies(b -> assertThat(b.yearFrom()).isEqualTo(2019))
                .satisfies(b -> assertThat(b.models()).containsExactly(new CatalogModel("C1150", "Carnival")));
        assertThat(overview.sources().getFirst().source()).isEqualTo(CarSource.GUAZI); // guazi first

        settings.saveCatalog(CarSource.AUTOWINI, List.of(new CatalogBrand("C0810", "Kia", null)));
        settings.saveModels(CarSource.AUTOWINI, "C0810", "Kia", List.of(new CatalogModel("C1150", "Carnival")));
        settings.saveCatalog(CarSource.AUTOWINI, List.of(new CatalogBrand("C0810", "KIA", null))); // models kept
        assertThat(settings.catalog(CarSource.AUTOWINI)).singleElement()
                .satisfies(b -> assertThat(b.name()).isEqualTo("KIA"))
                .satisfies(b -> assertThat(b.models()).hasSize(1));
    }
}
