package com.premiumcars.autoimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.premiumcars.common.NotFoundException;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.social.PostedChannels;

/**
 * The run's bookkeeping against PostgreSQL 17 in Docker, with a made-up search instead of the sites: new listings
 * queued, cars imported by hand linked, deleted once their listing is gone (with the report), the 20 % guard,
 * incomplete makes.
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
            return new BrandPass(brand.code(), brand.name(), list.size(), list.size(), whole, whole ? null : "cut", false);
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
        assertThat(service.overview().runsHere()).isTrue(); // app.auto-import.enabled defaults to true

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

        // the queue emptied by hand: the next run queues again what is still at the source
        service.clearQueue(CarSource.GUAZI);
        assertThat(listings.find(CarSource.GUAZI, "bbb2")).isEmpty();
        assertThat(listings.find(CarSource.GUAZI, "aaa1")).isPresent(); // imported: kept
        assertThat(runner.pass(search, GUAZI, false).newCount()).isEqualTo(1);
        assertThat(listings.find(CarSource.GUAZI, "bbb2").orElseThrow().state()).isEqualTo("QUEUED");

        // a reserved car of the source is kept when its listing goes
        var reserved = carService.createDraft(new ImportedCar("rrr3", null, CarSpecs.builder().title("BYD Han").build(),
                new BigDecimal("15000"), "USD", List.of(), List.of()), CarSource.GUAZI, listing("rrr3").url());
        carService.changeStatus(reserved.id(), CarStatus.RESERVED);
        search.results.put("1212", List.of(listing("aaa1"), listing("bbb2"), listing("rrr3")));
        runner.pass(search, GUAZI, false);

        // aaa1 and rrr3 gone: aaa1's car deleted at once and noted, the reserved one kept
        search.results.put("1212", List.of(listing("bbb2")));
        long runId = runs.start(CarSource.GUAZI, false);
        Result once = runner.pass(search, GUAZI, false, runId);
        assertThat(once.goneCount()).isEqualTo(2);
        assertThatThrownBy(() -> carService.getAdmin(manual.id())).isInstanceOf(NotFoundException.class);
        assertThat(carService.getAdmin(reserved.id()).status()).isEqualTo(CarStatus.RESERVED);
        assertThat(listings.find(CarSource.GUAZI, "aaa1")).isEmpty();
        assertThat(runs.removed(runId)).singleElement()
                .satisfies(r -> assertThat(r.title()).isEqualTo("BYD Seal"))
                .satisfies(r -> assertThat(r.carId()).isNull()); // the car is gone, the report stays
        carService.delete(reserved.id());

        // a make not read whole: a listing not seen this time stays (it goes after STALE_DAYS days unseen)
        search.results.put("1212", List.of(listing("ccc4")));
        runner.pass(search, GUAZI, false);
        search.results.put("1212", List.of());
        search.complete.put("1212", false);
        Result cut = runner.pass(search, GUAZI, false);
        assertThat(cut.status()).isEqualTo("INCOMPLETE");
        assertThat(cut.goneCount()).isZero();
        assertThat(listings.find(CarSource.GUAZI, "ccc4")).isPresent();
        search.complete.remove("1212");

        // the guard: most listings vanish at once -> nothing removed
        List<FoundListing> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(listing("g" + i));
        }
        search.results.put("1292", many);
        search.results.put("1212", List.of(listing("bbb2"), listing("ccc4")));
        runner.pass(search, GUAZI, false);
        search.results.remove("1292");
        Result stopped = runner.pass(search, GUAZI, false);
        assertThat(stopped.status()).isEqualTo("STOPPED");
        assertThat(stopped.goneCount()).isZero();
        assertThat(listings.find(CarSource.GUAZI, "g0")).isPresent();
    }

    @Test
    void listingsOverTheMileageLimitAreSkippedUntilTheLimitAllowsThem() {
        assertThat(settings.maxMileageKm()).isEqualTo(200_000); // the default
        service.save(CarSource.AUTOWINI, new SourceUpdate(true, new SourceFilters(false, false, List.of()),
                List.of(new BrandUpdate("C9990", "Testmake", true, 2018, List.of(), null))));
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
    void listingsOverTheHighestPriceOrWithoutAFobPriceAreSetAside() {
        assertThat(settings.maxPriceEur()).isEqualTo(100_000); // the default
        service.save(CarSource.AUTOWINI, new SourceUpdate(true, new SourceFilters(false, false, List.of()),
                List.of(new BrandUpdate("C9993", "Pricemake", true, 2018, List.of(), null))));
        var autowini = new SourceSettings(CarSource.AUTOWINI, true, new SourceFilters(false, false, List.of()),
                Instant.now());
        FakeSearch search = new FakeSearch();
        FoundListing dear = new FoundListing("pr1", "https://www.autowini.com/items/pr1", "Dear", new BigDecimal("400000"),
                null);
        FoundListing cheap = new FoundListing("pr2", "https://www.autowini.com/items/pr2", "Cheap",
                new BigDecimal("20000"), null);
        search.results.put("C9993", List.of(dear, cheap));

        Result first = runner.pass(search, autowini, false);
        assertThat(first.message()).contains("1 over 100000 EUR FOB");
        assertThat(state("pr1")).isEqualTo(AutoImportListingRepository.PRICE_SKIPPED);
        assertThat(state("pr2")).isEqualTo("QUEUED");
        assertThat(listings.leftOutByBrand(CarSource.AUTOWINI)).containsEntry("C9993", 1);

        settings.setMaxPriceEur(null); // no limit: queued again
        runner.pass(search, autowini, false);
        assertThat(state("pr1")).isEqualTo("QUEUED");
        settings.setMaxPriceEur(100_000);

        // no FOB price on the page: set aside by its listing id for good, even when its search price changes
        listings.noPrice(CarSource.AUTOWINI, "pr2", "No price");
        runner.pass(search, autowini, false);
        assertThat(state("pr2")).isEqualTo(AutoImportListingRepository.NO_PRICE);
        search.results.put("C9993", List.of(dear, new FoundListing("pr2", cheap.url(), "Cheap",
                new BigDecimal("21000"), null)));
        Result again = runner.pass(search, autowini, false);
        assertThat(again.newCount()).isZero();
        assertThat(state("pr2")).isEqualTo(AutoImportListingRepository.NO_PRICE);
        assertThat(listings.leftOutByBrand(CarSource.AUTOWINI)).containsEntry("C9993", 2);
        assertThat(service.check(CarSource.AUTOWINI).cars()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aRefusingSiteKeepsTheListingQueuedAndPausesItsQueue() {
        service.save(CarSource.AUTOWINI, new SourceUpdate(true, new SourceFilters(false, false, List.of()),
                List.of(new BrandUpdate("C9994", "Blockmake", true, 2018, List.of(), null))));
        var autowini = new SourceSettings(CarSource.AUTOWINI, true, new SourceFilters(false, false, List.of()),
                Instant.now());
        FakeSearch search = new FakeSearch();
        search.results.put("C9994", List.of(new FoundListing("bl1", "https://www.autowini.com/items/bl1", "Blocked",
                new BigDecimal("20000"), 50_000)));
        runner.pass(search, autowini, false);
        assertThat(state("bl1")).isEqualTo("QUEUED");

        assertThat(worker.pausedUntil(CarSource.AUTOWINI)).isNull();
        listings.retryLater(CarSource.AUTOWINI, "bl1", "autowini returned HTTP 403");
        worker.refused(CarSource.AUTOWINI, new com.premiumcars.importing.SourceBlockedException(403, "HTTP 403"));
        assertThat(state("bl1")).isEqualTo("QUEUED");
        assertThat(listings.find(CarSource.AUTOWINI, "bl1").orElseThrow().attempts()).isZero();
        assertThat(worker.pausedUntil(CarSource.AUTOWINI)).isAfter(Instant.now().plusSeconds(14 * 60));
        assertThat(service.overview().sources()).filteredOn(v -> v.source() == CarSource.AUTOWINI).singleElement()
                .satisfies(v -> assertThat(v.pausedUntil()).isNotNull());
    }

    @Test
    void oneListingImportedTwiceIsFoundAndDeletedOnce() {
        var first = carService.createDraft(new ImportedCar("dupe7x", null, CarSpecs.builder().title("BYD Dupe").build(),
                new BigDecimal("15000"), "USD", List.of(), List.of()), CarSource.GUAZI,
                "https://en.guazi.com/products/byd-dupe-2023-gray-10000km-dupe7x.html");
        // the same listing after its km changed: another link
        var second = carService.createDraft(new ImportedCar("DUPE7X", null, CarSpecs.builder().title("BYD Dupe").build(),
                new BigDecimal("15000"), "USD", List.of(), List.of()), CarSource.GUAZI,
                "https://en.guazi.com/products/byd-dupe-2023-gray-12000km-dupe7x.html");
        var dupes = service.duplicates(CarSource.GUAZI);
        assertThat(dupes.sample()).anySatisfy(g -> {
            assertThat(g.keep()).isEqualTo(second.id()); // the newest when none is followed or posted
            assertThat(g.delete()).containsExactly(first.id());
            assertThat(g.cars()).extracting(AutoImportService.DuplicateCar::id).contains(first.id(), second.id());
        });
        service.deleteDuplicates(CarSource.GUAZI);
        assertThat(service.duplicates(CarSource.GUAZI).groups()).isZero();

        // found again by its id under a third link: no new car, the car takes the link
        String third = "https://en.guazi.com/products/byd-dupe-2023-gray-15000km-dupe7x.html";
        assertThat(carService.findListing(CarSource.GUAZI, third, "dupe7x")).get()
                .satisfies(c -> assertThat(c.id()).isEqualTo(second.id()))
                .satisfies(c -> assertThat(c.sourceUrl()).isEqualTo(third));
        carService.delete(second.id());
    }

    @Test
    void carsAddedByHandAreCheckedByTheirMakeAndDeletedWhenGone() {
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

        // found by its listing id under another link (guazi's link changes with the km): still there
        search.results.put("777", List.of(new FoundListing("hand1",
                "https://en.guazi.com/products/test-car-12000km-hand1.html", "Testmake X", new BigDecimal("15000"),
                null)));
        assertThat(runner.pass(search, GUAZI, false).manualGone()).isZero();

        search.results.put("777", List.of());
        assertThat(runner.pass(search, GUAZI, true).manualGone()).isEqualTo(1); // a dry run only counts
        assertThat(carService.getAdmin(car.id()).status()).isEqualTo(CarStatus.PUBLISHED);
        long runId = runs.start(CarSource.GUAZI, false);
        Result gone = runner.pass(search, GUAZI, false, runId);
        assertThat(gone.manualGone()).isEqualTo(1);
        assertThatThrownBy(() -> carService.getAdmin(car.id())).isInstanceOf(NotFoundException.class);
        assertThat(runs.removed(runId)).singleElement()
                .satisfies(r -> assertThat(r.manual()).isTrue())
                .satisfies(r -> assertThat(r.title()).isEqualTo("Testmake X 2023"))
                .satisfies(r -> assertThat(r.posted()).extracting(PostedChannels.Post::network)
                        .containsExactly("MOBILE_BG"));
    }

    @Test
    void failuresAreTriedThreeTimesPerRunAndListedWhole() {
        var autowini = new SourceSettings(CarSource.AUTOWINI, true, new SourceFilters(false, false, List.of()),
                Instant.now());
        service.save(CarSource.AUTOWINI, new SourceUpdate(true, new SourceFilters(false, false, List.of()),
                List.of(new BrandUpdate("C9991", "Failmake", true, 2018, List.of(), null))));
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
    void theSourcesMenuModelNamesTheCar() {
        FakeSearch search = new FakeSearch();
        var car = carService.createDraft(new ImportedCar("men1", null, CarSpecs.builder().title("BYD All New Seal")
                .model("All New Seal").build(), new BigDecimal("15000"), "USD", List.of(), List.of()),
                CarSource.GUAZI, listing("men1").url());
        search.results.put("1212", List.of(listing("men1").withModel("Seal")));

        assertThat(runner.pass(search, GUAZI, true).newCount()).isZero(); // a dry run renames nothing
        assertThat(carService.getAdmin(car.id()).specs().model()).isEqualTo("All New Seal");

        Result r = runner.pass(search, GUAZI, false);
        assertThat(carService.getAdmin(car.id()).specs().model()).isEqualTo("Seal");
        assertThat(listings.find(CarSource.GUAZI, "men1").orElseThrow().model()).isEqualTo("Seal");
        assertThat(r.message()).contains("1 cars' model renamed");
        carService.delete(car.id());
    }

    @Autowired
    AutoImportExport exporter;

    @Test
    void theExportHoldsTheListingsAndTheRuns() throws Exception {
        FakeSearch search = new FakeSearch();
        search.results.put("1212", List.of(listing("exp1")));
        long runId = runs.start(CarSource.GUAZI, false);
        runs.finish(runId, runner.pass(search, GUAZI, false, runId));
        var bytes = new java.io.ByteArrayOutputStream();
        exporter.write(CarSource.GUAZI, bytes);
        Map<String, String> files = new HashMap<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                files.put(e.getName(), new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertThat(files.get("listings.csv")).startsWith("\uFEFFlisting_id,state,").contains("exp1,");
        assertThat(files.get("runs.csv")).startsWith("\uFEFFid,dry_run,status").contains(String.valueOf(runId));
        assertThat(AutoImportExport.cell("a,\"b\"")).isEqualTo("\"a,\"\"b\"\"\"");
    }

    @Test
    void settingsAreSavedWhole() {
        var update = new SourceUpdate(true, new SourceFilters(true, true, List.of()), List.of(
                new BrandUpdate("C0810", "Kia", true, 2019, List.of(new AutoImportDtos.ModelRef("C1150", "Carnival",
                        null), new AutoImportDtos.ModelRef("C1300", "Niro", List.of("Electric Vehicle (EV)",
                        " Gasoline+Electric "))), List.of("Diesel")),
                new BrandUpdate("C0120", "BMW", false, 2016, List.of(), List.of("Others"))));
        var overview = service.save(CarSource.AUTOWINI, update);
        var autowini = overview.sources().stream().filter(s -> s.source() == CarSource.AUTOWINI).findFirst()
                .orElseThrow();
        assertThat(autowini.enabled()).isTrue();
        assertThat(autowini.brands()).hasSize(2);
        assertThat(autowini.brands()).filteredOn(b -> b.code().equals("C0810")).singleElement()
                .satisfies(b -> assertThat(b.yearFrom()).isEqualTo(2019))
                .satisfies(b -> assertThat(b.models()).containsExactly(new CatalogModel("C1150", "Carnival"),
                        new CatalogModel("C1300", "Niro", List.of("Electric Vehicle (EV)", "Gasoline+Electric"))))
                // the make's own exclusions only while no model is chosen
                .satisfies(b -> assertThat(b.excludedFuels()).isEmpty());
        assertThat(autowini.brands()).filteredOn(b -> b.code().equals("C0120")).singleElement()
                .satisfies(b -> assertThat(b.excludedFuels()).containsExactly("Others"));
        assertThat(overview.sources().getFirst().source()).isEqualTo(CarSource.GUAZI); // guazi first

        settings.saveCatalog(CarSource.AUTOWINI, List.of(new CatalogBrand("C0810", "Kia", null)));
        settings.saveModels(CarSource.AUTOWINI, "C0810", "Kia", List.of(new CatalogModel("C1150", "Carnival")));
        settings.saveCatalog(CarSource.AUTOWINI, List.of(new CatalogBrand("C0810", "KIA", null))); // models kept
        assertThat(settings.catalog(CarSource.AUTOWINI)).singleElement()
                .satisfies(b -> assertThat(b.name()).isEqualTo("KIA"))
                .satisfies(b -> assertThat(b.models()).hasSize(1));
    }
}
