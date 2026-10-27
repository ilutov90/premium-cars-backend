package com.premiumcars.catalog.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.premiumcars.catalog.domain.CarFeature;
import com.premiumcars.catalog.domain.CarImage;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.Drivetrain;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.NewCarImage;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.domain.PricingInputs;
import com.premiumcars.catalog.domain.SpecGroup;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCar;
import com.premiumcars.catalog.web.dto.CarDtos.CarSummary;
import com.premiumcars.catalog.web.dto.CarDtos.CarUpdateRequest;
import com.premiumcars.catalog.web.dto.CarFilter;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.delivery.DeliveredCarDtos.DeliveredCarRequest;
import com.premiumcars.delivery.DeliveredCarService;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.inquiry.Attribution;
import com.premiumcars.inquiry.HeardFrom;
import com.premiumcars.inquiry.Inquiry;
import com.premiumcars.inquiry.InquiryDtos.InquiryRequest;
import com.premiumcars.inquiry.InquiryService;
import com.premiumcars.mobilebg.MobileBgCatalog;
import com.premiumcars.mobilebg.MobileBgListing;
import com.premiumcars.mobilebg.MobileBgRepository;
import com.premiumcars.social.PostSettings;
import com.premiumcars.social.PostSettingsRepository;
import com.premiumcars.social.PostTemplate;
import com.premiumcars.social.PostTemplateRepository;
import com.premiumcars.social.PublicContactController;
import com.premiumcars.social.ScheduledPost;
import com.premiumcars.social.ScheduledPostRepository;
import com.premiumcars.social.ScheduledPostService;
import com.premiumcars.social.SocialNetwork;
import com.premiumcars.social.SocialPost;
import com.premiumcars.social.SocialPostRepository;
import com.premiumcars.social.SocialPublisher;
import com.premiumcars.social.TemplateKey;
import com.premiumcars.pricing.PriceProfile;
import com.premiumcars.pricing.PricingProfile;

/**
 * Runs the real SQL through Spring's JdbcClient and the PostgreSQL driver, against PostgreSQL 17 in Docker.
 * Liquibase creates the schema and the three demo cars. Skipped when Docker is not available.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class JdbcRepositoriesTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    CarService carService;

    @Autowired
    InquiryService inquiryService;

    @Autowired
    CarImageRepository carImages;

    @Autowired
    SocialPostRepository socialPosts;

    @Autowired
    SocialPublisher socialPublisher;

    @Autowired
    ScheduledPostRepository scheduledPosts;

    @Autowired
    PostTemplateRepository templates;

    @Autowired
    MobileBgRepository mobileBg;

    @Autowired
    PostSettingsRepository postSettings;

    @Autowired
    PublicContactController publicContact;

    @Autowired
    DeliveredCarService deliveredCars;

    @Test
    void publicCatalogSortsWithNullsLast() {
        // The original bug: sorting crashed with "Null Precedence ... not yet supported" under JPA.
        for (String sort : new String[]{null, "price_asc", "price_desc", "year_desc", "mileage_asc"}) {
            var page = carService.searchPublic(null, sort, 0, 12);
            assertThat(page.items()).isNotEmpty();
            assertThat(page.items()).allMatch(c -> c.status() == CarStatus.PUBLISHED || c.status() == CarStatus.RESERVED);
        }
        var cheapestFirst = carService.searchPublic(null, "price_asc", 0, 12).items().stream()
                .map(CarSummary::totalPriceEur).toList();
        assertThat(cheapestFirst).isSortedAccordingTo(BigDecimal::compareTo);
    }

    @Test
    void filtersAndPaging() {
        var electric = new CarFilter(null, null, FuelType.ELECTRIC, null, null, null, null, null, null, null);
        assertThat(carService.searchPublic(electric, null, 0, 12).items())
                .extracting(CarSummary::make).containsOnly("BYD");

        var page = carService.searchPublic(null, null, 0, 1);
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo((int) page.totalItems());
    }

    @Test
    void draftLifecycleWithImages() {
        String url = "https://global.autohome.com.cn/en/test/" + UUID.randomUUID();
        CarSpecs importedSpecs = CarSpecs.builder()
                .title("BMW X3 2022 xDrive30i").make("BMW").model("X3").year(2022).mileageKm(30_000)
                .transmission(Transmission.AUTOMATIC).extraSpecs(Map.of("Drive", "AWD"))
                .sheet(List.of(new SpecGroup("Engine", List.of(new SpecGroup.Item("Maximum horsepower (Ps)", "292"))),
                        new SpecGroup("Seat Features", List.of(new SpecGroup.Item("Front Seat Features", "Heating"),
                                new SpecGroup.Item("Paddle Shifters", null))),
                        new SpecGroup("Empty", List.of())))
                .build();
        var imported = new ImportedCar("123456", "xian", importedSpecs, new BigDecimal("20000"), "USD",
                List.of(), List.of());

        AdminCar draft = carService.createDraft(imported, CarSource.AUTOHOME, url);
        assertThat(draft.status()).isEqualTo(CarStatus.DRAFT);
        assertThat(draft.slug()).isEqualTo("bmw-x3-2022-" + draft.id());
        assertThat(draft.specs().extraSpecs()).containsEntry("Drive", "AWD");
        assertThat(draft.specs().sheet()).extracting(SpecGroup::name).containsExactly("Engine", "Seat Features");
        assertThat(draft.specs().sheet().get(1).items()).containsExactly(
                new SpecGroup.Item("Front Seat Features", "Heating"), new SpecGroup.Item("Paddle Shifters", ""));
        assertThat(draft.sourceLocation()).isEqualTo("xian");
        // 20000 USD * 0.86 = 17200 EUR; autohome: + 2000 sea + 1600 land + 500 commission
        // + 20 % of (17200 + 2000) = 3840 + 10 % of 17200 = 1720 profit = 26860
        assertThat(draft.pricing().profile()).isEqualTo(PriceProfile.AUTOHOME);
        assertThat(draft.price().totalEur()).isEqualByComparingTo("26860");
        assertThat(draft.price().dutyVatEur()).isEqualByComparingTo("3840");
        assertThat(carService.findBySourceUrl(url)).isPresent();

        // drafts are not public
        assertThatThrownBy(() -> carService.getPublic(draft.slug())).isInstanceOf(NotFoundException.class);

        AdminCar withImages = carService.attachImages(draft.id(), List.of(
                new NewCarImage("cars/test/a.jpg", null),
                new NewCarImage("cars/test/b.jpg", null),
                new NewCarImage("cars/test/c.jpg", "https://cdn.example.com/c.jpg"),
                new NewCarImage(null, "https://cdn.example.com/d.jpg"))); // linked: shown from the source
        assertThat(withImages.images()).extracting(i -> i.position()).containsExactly(0, 1, 2, 3);
        assertThat(withImages.images().get(3).linked()).isTrue();
        assertThat(withImages.images().get(3).url()).isEqualTo("https://cdn.example.com/d.jpg");
        assertThat(withImages.images().get(0).url()).endsWith("cars/test/a.jpg");
        assertThat(withImages.images().get(3).fileUrl())
                .isEqualTo("/media/photo/" + withImages.images().get(3).id());

        // stored photos of a site that now links them: linked one by one (StoredPhotoLinker)
        assertThat(carImages.findStored(List.of("AUTOHOME"))).extracting(CarImage::id)
                .contains(withImages.images().get(2).id())
                .doesNotContain(withImages.images().get(0).id(), withImages.images().get(3).id()); // no URL / linked
        assertThat(carImages.findStored(List.of("AUTOWINI"))).extracting(CarImage::carId).doesNotContain(draft.id());
        carImages.link(withImages.images().get(2).id(), "https://cdn.example.com/c-large.jpg");
        assertThat(carImages.findById(withImages.images().get(2).id()).orElseThrow())
                .satisfies(i -> assertThat(i.linked()).isTrue())
                .extracting(CarImage::sourceUrl).isEqualTo("https://cdn.example.com/c-large.jpg");

        long third = withImages.images().get(2).id();
        AdminCar covered = carService.makeCover(draft.id(), third);
        assertThat(covered.images().getFirst().id()).isEqualTo(third);

        AdminCar fewer = carService.deleteImage(draft.id(), withImages.images().getFirst().id());
        assertThat(fewer.images()).extracting(i -> i.position()).containsExactly(0, 1, 2);

        CarSpecs edited = draft.specs().toBuilder()
                .title("BMW X3 xDrive30i M Sport").trim("  M Sport ").mileageKm(31_000)
                .fuelType(FuelType.PLUGIN_HYBRID).drivetrain(Drivetrain.AWD).engineCc(1998).powerHp(292)
                .batteryKwh(new BigDecimal("19.7")).electricRangeKm(88).rangeStandard("WLTP").motorPowerKw(80)
                .batteryHealthPct(93).bodyType("SUV").seats(5).doors(5).curbWeightKg(2190).dimensions("4708 × 1891 × 1676")
                .firstRegistration("2022-04").color(" ").euroStandard(6)
                .extraSpecs(Map.of("Drive", "AWD", "Seats", "5"))
                .features(List.of(CarFeature.NAVIGATION, CarFeature.ABS, CarFeature.NAVIGATION))
                .sheet(null) // the admin panel does not send the imported sheet
                .build();
        var update = new CarUpdateRequest(edited,
                new PricingInputs(new BigDecimal("21000"), "usd", null, null, null));
        AdminCar updated = carService.update(draft.id(), update);
        assertThat(updated.pricing().sourceCurrency()).isEqualTo("USD");
        assertThat(updated.pricing().profile()).isEqualTo(PriceProfile.AUTOHOME); // null = keep the site's formula
        assertThat(updated.price().totalEur()).isEqualByComparingTo("27978"); // 18060 + 2000 + 1600 + 500 + 4012 + 1806
        assertThat(updated.slug()).isEqualTo("bmw-x3-2022-" + draft.id());
        CarSpecs saved = updated.specs();
        assertThat(saved.trim()).isEqualTo("M Sport");
        assertThat(saved.color()).isNull();
        assertThat(saved.fuelType()).isEqualTo(FuelType.PLUGIN_HYBRID);
        assertThat(saved.drivetrain()).isEqualTo(Drivetrain.AWD);
        assertThat(saved.batteryKwh()).isEqualByComparingTo("19.7");
        assertThat(saved.electricRangeKm()).isEqualTo(88);
        assertThat(saved.rangeStandard()).isEqualTo("WLTP");
        assertThat(saved.motorPowerKw()).isEqualTo(80);
        assertThat(saved.batteryHealthPct()).isEqualTo(93);
        assertThat(saved.engineCc()).isEqualTo(1998);
        assertThat(saved.seats()).isEqualTo(5);
        assertThat(saved.curbWeightKg()).isEqualTo(2190);
        assertThat(saved.firstRegistration()).isEqualTo("2022-04");
        assertThat(saved.extraSpecs()).containsExactly(Map.entry("Drive", "AWD"), Map.entry("Seats", "5"));
        assertThat(saved.euroStandard()).isEqualTo(6);
        assertThat(saved.features()).containsExactly(CarFeature.ABS, CarFeature.NAVIGATION); // form order, no duplicates
        assertThat(saved.sheet()).isEqualTo(draft.specs().sheet()); // saving keeps the imported sheet

        AdminCar published = carService.changeStatus(draft.id(), CarStatus.PUBLISHED);
        assertThat(published.publishedAt()).isNotNull();
        var publicCar = carService.getPublic(published.slug());
        assertThat(publicCar.imageUrls()).hasSize(3).contains("https://cdn.example.com/d.jpg");
        assertThat(publicCar.priceEur()).isEqualByComparingTo("27978");
        assertThat(publicCar.specs().batteryKwh()).isEqualByComparingTo("19.7");
        assertThat(publicCar.specs().sheet()).hasSize(2);
        assertThat(carService.searchPublic(null, null, 0, 48).items())
                .anySatisfy(c -> assertThat(c.electricRangeKm()).isEqualTo(88));
        assertThat(carService.changeStatus(draft.id(), CarStatus.RESERVED).publishedAt())
                .isEqualTo(published.publishedAt());

        var inquiry = inquiryService.create(new InquiryRequest("Ivan", "0888123456", null, "Hello", draft.id(), null, true,
                null, null));
        assertThat(inquiry.id()).isNotNull();
        var list = inquiryService.list(Inquiry.Status.NEW, 0, 50);
        assertThat(list.items()).anySatisfy(i -> assertThat(i.carTitle()).isEqualTo("BMW X3 xDrive30i M Sport"));
        assertThat(inquiryService.changeStatus(inquiry.id(), Inquiry.Status.CONTACTED).status())
                .isEqualTo(Inquiry.Status.CONTACTED);

        carService.delete(draft.id());
        assertThatThrownBy(() -> carService.getAdmin(draft.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void pricesAreKeptUntilTheCarsOwnPriceChanges() {
        String url = "https://www.autowini.com/test/" + UUID.randomUUID();
        var imported = new ImportedCar("IC1", null, CarSpecs.builder().title("Kia K5 2021").build(),
                new BigDecimal("25000"), "EUR", List.of(), List.of());
        AdminCar car = carService.createDraft(imported, CarSource.AUTOWINI, url);
        // 25000 + 1200 sea + 1600 land + 500 commission + 17 % of 26200 (4454) + 10 % of 25000 (2500)
        assertThat(car.price().totalEur()).isEqualByComparingTo("35254");
        assertThat(car.price().eurPerUnit()).isEqualByComparingTo("1");

        PricingProfile original = carService.pricingProfiles().stream()
                .filter(p -> p.id() == PriceProfile.AUTOWINI).findFirst().orElseThrow();
        try {
            carService.updatePricingProfile(PriceProfile.AUTOWINI, new PricingProfile(null,
                    new BigDecimal("1300"), new BigDecimal("1600"), new BigDecimal("500"), new BigDecimal("12"),
                    new BigDecimal("1000"), new BigDecimal("17"), null));
            // a new formula does not touch existing prices (they may be posted already)
            assertThat(carService.getAdmin(car.id()).price().totalEur()).isEqualByComparingTo("35254");
            assertThat(carService.getAdmin(car.id()).price().seaFreightEur()).isEqualByComparingTo("1200");

            // saving other fields keeps the price, also with amounts written differently (25000 vs 25000.00)
            var renamed = carService.update(car.id(), new CarUpdateRequest(
                    car.specs().toBuilder().title("Kia K5 2021 GT").build(),
                    new PricingInputs(new BigDecimal("25000.0"), "EUR", PriceProfile.AUTOWINI, null, null)));
            assertThat(renamed.price().totalEur()).isEqualByComparingTo("35254");

            // changing the car's own price fields recalculates it, with today's formula
            var withProfit = carService.update(car.id(), new CarUpdateRequest(car.specs(),
                    new PricingInputs(new BigDecimal("25000"), "EUR", null, new BigDecimal("4000"), null)));
            assertThat(withProfit.price().totalEur()).isEqualByComparingTo("36871"); // 25000 + 1300 + 1600 + 500 + 4471 + 4000

            // recalculating by hand: today's rate and formula
            var back = carService.update(car.id(), new CarUpdateRequest(car.specs(),
                    new PricingInputs(new BigDecimal("25000"), "EUR", null, null, null)));
            assertThat(back.price().totalEur()).isEqualByComparingTo("35871"); // 25000 + 1300 + 1600 + 500 + 4471 + 12 % of 25000
            assertThat(carService.recalculatePrice(car.id()).price().totalEur()).isEqualByComparingTo("35871");

            var fixed = carService.update(car.id(), new CarUpdateRequest(car.specs(),
                    new PricingInputs(new BigDecimal("25000"), "EUR", null, null, new BigDecimal("29990"))));
            assertThat(fixed.price().totalEur()).isEqualByComparingTo("29990");
            assertThat(fixed.pricing().fixedTotalEur()).isEqualByComparingTo("29990");
            carService.recalculateAllPrices();
            assertThat(carService.getAdmin(car.id()).price().totalEur()).isEqualByComparingTo("29990");
        } finally {
            carService.updatePricingProfile(PriceProfile.AUTOWINI, original);
            carService.delete(car.id());
        }
    }

    @Test
    void adminListShowsAndFindsTheSourceListing() {
        String ref = "IC" + UUID.randomUUID().toString().substring(0, 8);
        String url = "https://www.autowini.com/goods/detail?listingId=" + ref;
        var imported = new ImportedCar(ref, null, CarSpecs.builder().title("Hyundai Tucson 2023").build(),
                new BigDecimal("20000"), "EUR", List.of(), List.of());
        AdminCar car = carService.createDraft(imported, CarSource.AUTOWINI, url);

        var byLink = carService.searchAdmin(null, url, 0, 10).items();
        assertThat(byLink).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(car.id());
            assertThat(row.source()).isEqualTo(CarSource.AUTOWINI);
            assertThat(row.sourceRef()).isEqualTo(ref);
            assertThat(row.sourceUrl()).isEqualTo(url);
        });
        assertThat(carService.searchAdmin(null, ref.toLowerCase(), 0, 10).items()).hasSize(1);

        carService.delete(car.id());
    }

    @Test
    void socialPostsAreRecordedPerCarAndGoWithTheCar() {
        String url = "https://www.autowini.com/test/" + UUID.randomUUID();
        var imported = new ImportedCar("IC2", null, CarSpecs.builder().title("Kia Sportage 2024").build(),
                new BigDecimal("20000"), "EUR", List.of(), List.of());
        AdminCar car = carService.createDraft(imported, CarSource.AUTOWINI, url);

        socialPosts.insert(car.id(), SocialNetwork.FACEBOOK, "PAGE_1", "https://www.facebook.com/PAGE_1", 10);
        socialPosts.insert(car.id(), SocialNetwork.INSTAGRAM, "media1", null, 8);
        socialPosts.insert(car.id(), SocialNetwork.VIBER, "5491", null, 6);
        assertThat(socialPublisher.posts(car.id())).extracting(SocialPost::network)
                .containsExactlyInAnyOrder(SocialNetwork.FACEBOOK, SocialNetwork.INSTAGRAM, SocialNetwork.VIBER);

        // a car without photos cannot be published (checked before any network call)
        assertThatThrownBy(() -> socialPublisher.publish(car.id(), SocialNetwork.FACEBOOK, "text"))
                .hasMessageContaining("no photos");
        // only the car's own photos can be picked
        assertThatThrownBy(() -> socialPublisher.publish(car.id(), SocialNetwork.VIBER, "text", List.of(-1L)))
                .hasMessageContaining("is not a photo of car");

        carService.delete(car.id());
        assertThat(socialPosts.findByCarId(car.id())).isEmpty();
    }

    @Test
    void scheduledPostsAreClaimedOnceAndGoWithTheCar() {
        String url = "https://www.autowini.com/test/" + UUID.randomUUID();
        var imported = new ImportedCar("IC3", null, CarSpecs.builder().title("Hyundai Santa Fe 2021").build(),
                new BigDecimal("15526"), "EUR", List.of(), List.of());
        AdminCar car = carService.createDraft(imported, CarSource.AUTOWINI, url);
        var now = java.time.Instant.now();

        ScheduledPost due = scheduledPosts.insert(car.id(), SocialNetwork.VIBER, "Viber text", List.of(3L, 1L, 2L),
                now.minusSeconds(5), "admin");
        ScheduledPost later = scheduledPosts.insert(car.id(), SocialNetwork.FACEBOOK, "FB text", null,
                now.plusSeconds(3600), "admin");
        assertThat(due.imageIds()).containsExactly(3L, 1L, 2L);
        assertThat(due.carTitle()).isEqualTo("Hyundai Santa Fe 2021");
        assertThat(later.imageIds()).isEmpty();
        assertThat(due.status()).isEqualTo(ScheduledPost.Status.SCHEDULED);

        // only the due one is taken, and only once
        List<ScheduledPost> claimed = scheduledPosts.claimDue(5);
        assertThat(claimed).extracting(ScheduledPost::id).containsExactly(due.id());
        assertThat(claimed.getFirst().status()).isEqualTo(ScheduledPost.Status.PUBLISHING);
        assertThat(claimed.getFirst().attempts()).isEqualTo(1);
        assertThat(scheduledPosts.claimDue(5)).isEmpty();

        // publishing now: one post is taken by its id, once
        ScheduledPost immediate = scheduledPosts.insert(car.id(), SocialNetwork.FACEBOOK, "now", null,
                java.time.Instant.now(), "admin");
        assertThat(scheduledPosts.claim(immediate.id())).get().extracting(ScheduledPost::status)
                .isEqualTo(ScheduledPost.Status.PUBLISHING);
        assertThat(scheduledPosts.claim(immediate.id())).isEmpty();
        scheduledPosts.markFailed(immediate.id(), "test");

        // a post being published cannot be cancelled; a waiting one can
        assertThat(scheduledPosts.cancel(due.id())).isFalse();
        assertThat(scheduledPosts.cancel(later.id())).isTrue();
        assertThat(scheduledPosts.cancel(later.id())).isFalse();

        SocialPost posted = socialPosts.insert(car.id(), SocialNetwork.VIBER, "5492", null, 3);
        scheduledPosts.markPublished(due.id(), posted.id());
        assertThat(scheduledPosts.findById(due.id()).orElseThrow())
                .extracting(ScheduledPost::status, ScheduledPost::socialPostId)
                .containsExactly(ScheduledPost.Status.PUBLISHED, posted.id());
        assertThat(scheduledPosts.findByCarId(car.id())).hasSize(3);
        assertThat(scheduledPosts.findUpcomingAndRecent(now.minusSeconds(3600)))
                .extracting(ScheduledPost::id).contains(due.id(), later.id());

        // a post left PUBLISHING by a restart becomes FAILED, not published twice
        ScheduledPost interrupted = scheduledPosts.insert(car.id(), SocialNetwork.INSTAGRAM, "IG", null,
                now.minusSeconds(5), "admin");
        assertThat(scheduledPosts.claimDue(5)).extracting(ScheduledPost::id).containsExactly(interrupted.id());
        assertThat(scheduledPosts.failInterrupted(java.time.Instant.now().plusSeconds(1))).isEqualTo(1);
        assertThat(scheduledPosts.findById(interrupted.id()).orElseThrow().status())
                .isEqualTo(ScheduledPost.Status.FAILED);

        carService.delete(car.id());
        assertThat(scheduledPosts.findByCarId(car.id())).isEmpty();
    }

    @Test
    void aPublicationRecordsOnePostPerNetworkInOrder() {
        String url = "https://www.autowini.com/test/" + UUID.randomUUID();
        var imported = new ImportedCar("IC4", null, CarSpecs.builder().title("Kia K5 2022").build(),
                new BigDecimal("14000"), "EUR", List.of(), List.of());
        AdminCar car = carService.createDraft(imported, CarSource.AUTOWINI, url);
        var at = java.time.Instant.now().plusSeconds(600);

        List<ScheduledPost> posts = scheduledPosts.insertAll(car.id(), List.of(
                        new ScheduledPostService.Draft(SocialNetwork.FACEBOOK, "shared"),
                        new ScheduledPostService.Draft(SocialNetwork.INSTAGRAM, "shared"),
                        new ScheduledPostService.Draft(SocialNetwork.VIBER, "viber")),
                List.of(2L, 1L), at, "admin");

        assertThat(posts).extracting(ScheduledPost::network)
                .containsExactly(SocialNetwork.FACEBOOK, SocialNetwork.INSTAGRAM, SocialNetwork.VIBER);
        assertThat(posts).extracting(ScheduledPost::body).containsExactly("shared", "shared", "viber");
        assertThat(posts).allSatisfy(p -> {
            assertThat(p.imageIds()).containsExactly(2L, 1L);
            assertThat(p.status()).isEqualTo(ScheduledPost.Status.SCHEDULED);
        });
        assertThat(scheduledPosts.findByCarId(car.id())).hasSize(3);
    }

    @Test
    void mobileBgListsAndListings() {
        assertThat(mobileBg.catalog()).isNotNull();
        var brands = List.of(new MobileBgCatalog.Brand("Hyundai", List.of("Santa Fe", "Tucson")),
                new MobileBgCatalog.Brand("Mercedes-Benz", List.of("E 300")));
        var catalog = new MobileBgCatalog(brands, Map.of("f17", List.of(new MobileBgCatalog.Option("Сив", "Сив"))),
                List.of(new MobileBgCatalog.Extra("f70", "Климатик")), java.time.Instant.parse("2026-10-24T10:00:00Z"));
        mobileBg.saveCatalog(catalog, "admin");
        MobileBgCatalog read = mobileBg.catalog();
        assertThat(read.brands()).extracting(MobileBgCatalog.Brand::name).containsExactly("Hyundai", "Mercedes-Benz");
        assertThat(read.brands().getFirst().models()).containsExactly("Santa Fe", "Tucson");
        assertThat(read.options("f17")).containsExactly(new MobileBgCatalog.Option("Сив", "Сив"));
        assertThat(read.extras()).containsExactly(new MobileBgCatalog.Extra("f70", "Климатик"));
        assertThat(read.capturedAt()).isEqualTo(java.time.Instant.parse("2026-10-24T10:00:00Z"));

        String url = "https://www.autowini.com/test/" + UUID.randomUUID();
        var imported = new ImportedCar("IC9", null, CarSpecs.builder().title("Hyundai Santa Fe 2021").make("Hyundai")
                .model("SantaFe").year(2021).build(), new BigDecimal("18594"), "EUR", List.of(), List.of());
        AdminCar car = carService.createDraft(imported, CarSource.AUTOWINI, url);
        assertThat(mobileBg.listing(car.id())).isEmpty();
        mobileBg.saveListing(car.id(), "11792827040577366", "https://www.mobile.bg/obiava-11792827040577366-x",
                java.time.Instant.parse("2026-10-24T11:00:00Z"), "admin");
        MobileBgListing listing = mobileBg.saveListing(car.id(), "2", null,
                java.time.Instant.parse("2026-10-24T12:00:00Z"), "maria"); // posted again: the last one wins
        assertThat(listing.listingId()).isEqualTo("2");
        assertThat(listing.url()).isNull();
        assertThat(listing.publishedBy()).isEqualTo("maria");
        carService.delete(car.id());
        assertThat(mobileBg.listing(car.id())).isEmpty(); // goes with the car
    }

    @Test
    void postTemplatesAreSavedPerNetworkAndReset() {
        templates.save(TemplateKey.VIBER, "🎯{{title}}\n🔹Година: {{year}}");
        templates.save(TemplateKey.VIBER, "🎯{{title}}");
        templates.save(TemplateKey.FACEBOOK, "{{title}} {{price}}");
        templates.save(TemplateKey.MOBILE_BG, "{{model}}, {{year}}");

        assertThat(templates.findAll()).extracting(PostTemplate::network, PostTemplate::body)
                .contains(tuple(TemplateKey.VIBER, "🎯{{title}}"), tuple(TemplateKey.FACEBOOK, "{{title}} {{price}}"),
                        tuple(TemplateKey.MOBILE_BG, "{{model}}, {{year}}"));

        templates.delete(TemplateKey.VIBER);
        templates.delete(TemplateKey.FACEBOOK);
        templates.delete(TemplateKey.MOBILE_BG);
        assertThat(templates.findAll()).extracting(PostTemplate::network)
                .doesNotContain(TemplateKey.VIBER, TemplateKey.FACEBOOK, TemplateKey.MOBILE_BG);
    }

    @Test
    void postSettingsAreSavedAndBlankMeansDefault() {
        assertThat(postSettings.save(new PostSettings(" 0888123456 ", "https://invite.viber.com/x", "Пловдив",
                " 1234567890 "))).isEqualTo(new PostSettings("0888123456", "https://invite.viber.com/x", "Пловдив",
                "1234567890"));

        PostSettings back = postSettings.save(new PostSettings("0888123456", "  ", null, ""));
        assertThat(back).isEqualTo(new PostSettings("0888123456", null, null, null));
        assertThat(postSettings.find()).isEqualTo(back);

        assertThat(publicContact.contact()).isEqualTo(back); // the public site shows the same details

        postSettings.save(new PostSettings(null, null, null, null));
        assertThat(postSettings.find()).isEqualTo(new PostSettings(null, null, null, null));
    }

    @Test
    void inquiriesKeepWhereTheyCameFromAndTheReportCountsThem() {
        String campaign = "test-" + UUID.randomUUID();
        var withLink = new Attribution(" facebook ", "social", campaign, "reel-1", "/cars/x?utm_source=facebook",
                "l.facebook.com");
        inquiryService.create(new InquiryRequest("A", "0888000001", null, null, null, null, true,
                HeardFrom.FACEBOOK, withLink));
        inquiryService.create(new InquiryRequest("B", "0888000002", null, null, null, null, true,
                HeardFrom.FRIEND, withLink));
        inquiryService.create(new InquiryRequest("C", "0888000003", null, null, null, null, true, null,
                new Attribution("", " ", null, null, "x".repeat(400), null)));

        var newest = inquiryService.list(Inquiry.Status.NEW, 0, 3).items();
        assertThat(newest.get(0).heardFrom()).isNull();
        assertThat(newest.get(0).attribution().utmSource()).isNull();
        assertThat(newest.get(0).attribution().landingPath()).hasSize(300);
        assertThat(newest.get(1).heardFrom()).isEqualTo(HeardFrom.FRIEND);
        assertThat(newest.get(1).attribution()).isEqualTo(new Attribution("facebook", "social", campaign, "reel-1",
                "/cars/x?utm_source=facebook", "l.facebook.com"));

        var report = inquiryService.report(null, null);
        assertThat(report.to()).isEqualTo(report.from().plusDays(29));
        assertThat(report.total()).isGreaterThanOrEqualTo(3);
        assertThat(report.byUtmCampaign()).anySatisfy(c -> {
            assertThat(c.key()).isEqualTo(campaign);
            assertThat(c.count()).isEqualTo(2);
        });
        assertThat(report.byHeardFrom()).extracting(c -> c.key()).contains("FACEBOOK", "FRIEND", null);
        assertThat(report.byUtmSource()).extracting(c -> c.key()).contains("facebook");
        assertThat(report.byWeek()).isNotEmpty();
        assertThat(report.byWeek().stream().mapToLong(w -> w.count()).sum()).isEqualTo(report.total());
        assertThat(report.byCar()).anySatisfy(c -> assertThat(c.carId()).isNull());

        assertThat(inquiryService.report(report.to().plusDays(1), report.to().plusDays(7)).total()).isZero();
        assertThatThrownBy(() -> inquiryService.report(report.to(), report.from()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> inquiryService.report(report.to().minusYears(2), report.to()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void deliveredCarsArePublishedOnlyWithConsent() {
        String ref = "IC" + UUID.randomUUID().toString().substring(0, 8);
        AdminCar car = carService.createDraft(new ImportedCar(ref, null,
                CarSpecs.builder().title("Kia K5 2022").make("Kia").model("K5").build(), new BigDecimal("15000"),
                "EUR", List.of(), List.of()), CarSource.AUTOWINI,
                "https://www.autowini.com/goods/detail?listingId=" + ref);

        assertThatThrownBy(() -> deliveredCars.create(new DeliveredCarRequest(null, " ", "Георги", null, null, null,
                true, true))).isInstanceOf(BadRequestException.class); // no car
        assertThatThrownBy(() -> deliveredCars.create(new DeliveredCarRequest(null, "Kia K5", "Георги", null, null,
                null, false, true))).isInstanceOf(BadRequestException.class); // no consent

        var hidden = deliveredCars.create(new DeliveredCarRequest(car.id(), "", " Георги ", "Пловдив",
                " Доволен съм! ", LocalDate.of(2026, 10, 1), true, false));
        assertThat(hidden.carTitle()).isEqualTo(car.specs().title()); // taken from the car
        assertThat(hidden.customerName()).isEqualTo("Георги");
        assertThat(hidden.quote()).isEqualTo("Доволен съм!");
        assertThat(deliveredCars.listPublic(50)).noneMatch(d -> d.id() == hidden.id());

        var shown = deliveredCars.update(hidden.id(), new DeliveredCarRequest(car.id(), "Kia K5 2022", "Георги",
                "Пловдив", "Доволен съм!", LocalDate.of(2026, 10, 1), true, true));
        assertThat(shown.carTitle()).isEqualTo("Kia K5 2022");
        assertThat(deliveredCars.listPublic(50)).anySatisfy(d -> {
            assertThat(d.id()).isEqualTo(hidden.id());
            assertThat(d.carSlug()).isNull(); // the car is a draft: no link
            assertThat(d.photoUrl()).isNull();
        });

        byte[] jpeg = new byte[2048];
        jpeg[0] = (byte) 0xff;
        jpeg[1] = (byte) 0xd8;
        jpeg[2] = (byte) 0xff;
        jpeg[2046] = (byte) 0xff;
        jpeg[2047] = (byte) 0xd9;
        assertThat(deliveredCars.setPhoto(hidden.id(), jpeg).photoUrl()).endsWith(".jpg");
        assertThatThrownBy(() -> deliveredCars.setPhoto(hidden.id(), new byte[2048]))
                .isInstanceOf(BadRequestException.class);
        assertThat(deliveredCars.deletePhoto(hidden.id()).photoUrl()).isNull();

        carService.changeStatus(car.id(), CarStatus.PUBLISHED);
        assertThat(deliveredCars.listPublic(50)).filteredOn(d -> d.id() == hidden.id())
                .extracting(d -> d.carSlug()).containsExactly(car.slug());

        carService.delete(car.id()); // the story stays, without the link
        assertThat(deliveredCars.get(hidden.id()).carId()).isNull();
        deliveredCars.delete(hidden.id());
        assertThat(deliveredCars.listAdmin()).noneMatch(d -> d.id() == hidden.id());
    }

    @Test
    void guaziCarsUseTheAutohomeFormulaAndCountAsChina() {
        String ref = "gz" + UUID.randomUUID().toString().substring(0, 8);
        var imported = new ImportedCar(ref, "Shenyang, China", CarSpecs.builder().title("BYD Song MAX 2021")
                .make("BYD").build(), new BigDecimal("7603"), "USD", List.of(), List.of());
        AdminCar car = carService.createDraft(imported, CarSource.GUAZI,
                "https://en.guazi.com/products/byd-song-max-2021-" + ref + ".html");
        assertThat(car.pricing().profile()).isEqualTo(PriceProfile.AUTOHOME);
        assertThat(car.price()).isNotNull();

        carService.changeStatus(car.id(), CarStatus.PUBLISHED);
        var china = new CarFilter(null, null, null, null, Origin.CN, null, null, null, null, null);
        assertThat(carService.searchPublic(china, null, 0, 48).items())
                .anySatisfy(c -> {
                    assertThat(c.id()).isEqualTo(car.id());
                    assertThat(c.origin()).isEqualTo(Origin.CN);
                });
        carService.delete(car.id());
    }

    @Test
    void recalculatesAllPrices() {
        assertThat(carService.recalculateAllPrices()).isGreaterThanOrEqualTo(3);
    }
}
