package com.premiumcars.catalog.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.Drivetrain;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.NewCarImage;
import com.premiumcars.catalog.domain.PricingInputs;
import com.premiumcars.catalog.domain.Transmission;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.web.dto.CarDtos.AdminCar;
import com.premiumcars.catalog.web.dto.CarDtos.CarSummary;
import com.premiumcars.catalog.web.dto.CarDtos.CarUpdateRequest;
import com.premiumcars.catalog.web.dto.CarFilter;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.importing.ImportedCar;
import com.premiumcars.inquiry.Inquiry;
import com.premiumcars.inquiry.InquiryDtos.InquiryRequest;
import com.premiumcars.inquiry.InquiryService;
import com.premiumcars.pricing.PriceProfile;
import com.premiumcars.pricing.PricingProfile;
import com.premiumcars.pricing.StepMode;

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
                .build();
        var imported = new ImportedCar("123456", "xian", importedSpecs, new BigDecimal("20000"), "USD",
                List.of(), List.of());

        AdminCar draft = carService.createDraft(imported, CarSource.AUTOHOME, url);
        assertThat(draft.status()).isEqualTo(CarStatus.DRAFT);
        assertThat(draft.slug()).isEqualTo("bmw-x3-2022-" + draft.id());
        assertThat(draft.specs().extraSpecs()).containsEntry("Drive", "AWD");
        assertThat(draft.sourceLocation()).isEqualTo("xian");
        // 20000 USD * 0.86 = 17200 EUR; autohome: + 5860 + 2880 (one started 10 000 above 10 000) + 2000
        assertThat(draft.pricing().profile()).isEqualTo(PriceProfile.AUTOHOME);
        assertThat(draft.price().totalEur()).isEqualByComparingTo("27940");
        assertThat(carService.findBySourceUrl(url)).isPresent();

        // drafts are not public
        assertThatThrownBy(() -> carService.getPublic(draft.slug())).isInstanceOf(NotFoundException.class);

        AdminCar withImages = carService.attachImages(draft.id(), List.of(
                new NewCarImage("cars/test/a.jpg", null),
                new NewCarImage("cars/test/b.jpg", null),
                new NewCarImage("cars/test/c.jpg", null)));
        assertThat(withImages.images()).extracting(i -> i.position()).containsExactly(0, 1, 2);

        long third = withImages.images().get(2).id();
        AdminCar covered = carService.makeCover(draft.id(), third);
        assertThat(covered.images().getFirst().id()).isEqualTo(third);

        AdminCar fewer = carService.deleteImage(draft.id(), withImages.images().getFirst().id());
        assertThat(fewer.images()).extracting(i -> i.position()).containsExactly(0, 1);

        CarSpecs edited = draft.specs().toBuilder()
                .title("BMW X3 xDrive30i M Sport").trim("  M Sport ").mileageKm(31_000)
                .fuelType(FuelType.PLUGIN_HYBRID).drivetrain(Drivetrain.AWD).engineCc(1998).powerHp(292)
                .batteryKwh(new BigDecimal("19.7")).electricRangeKm(88).rangeStandard("WLTP").motorPowerKw(80)
                .bodyType("SUV").seats(5).doors(5).curbWeightKg(2190).dimensions("4708 × 1891 × 1676")
                .firstRegistration("2022-04").color(" ")
                .extraSpecs(Map.of("Drive", "AWD", "Seats", "5"))
                .build();
        var update = new CarUpdateRequest(edited,
                new PricingInputs(new BigDecimal("21000"), "usd", null));
        AdminCar updated = carService.update(draft.id(), update);
        assertThat(updated.pricing().sourceCurrency()).isEqualTo("USD");
        assertThat(updated.pricing().profile()).isEqualTo(PriceProfile.AUTOHOME); // null = keep the site's formula
        assertThat(updated.price().totalEur()).isEqualByComparingTo("28800"); // 18060 + 5860 + 2880 + 2000
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
        assertThat(saved.engineCc()).isEqualTo(1998);
        assertThat(saved.seats()).isEqualTo(5);
        assertThat(saved.curbWeightKg()).isEqualTo(2190);
        assertThat(saved.firstRegistration()).isEqualTo("2022-04");
        assertThat(saved.extraSpecs()).containsExactly(Map.entry("Drive", "AWD"), Map.entry("Seats", "5"));

        AdminCar published = carService.changeStatus(draft.id(), CarStatus.PUBLISHED);
        assertThat(published.publishedAt()).isNotNull();
        var publicCar = carService.getPublic(published.slug());
        assertThat(publicCar.imageUrls()).hasSize(2);
        assertThat(publicCar.priceEur()).isEqualByComparingTo("28800");
        assertThat(publicCar.specs().batteryKwh()).isEqualByComparingTo("19.7");
        assertThat(carService.searchPublic(null, null, 0, 48).items())
                .anySatisfy(c -> assertThat(c.electricRangeKm()).isEqualTo(88));
        assertThat(carService.changeStatus(draft.id(), CarStatus.RESERVED).publishedAt())
                .isEqualTo(published.publishedAt());

        var inquiry = inquiryService.create(new InquiryRequest("Ivan", "0888123456", null, "Hello", draft.id(), null));
        assertThat(inquiry.id()).isNotNull();
        var list = inquiryService.list(Inquiry.Status.NEW, 0, 50);
        assertThat(list.items()).anySatisfy(i -> assertThat(i.carTitle()).isEqualTo("BMW X3 xDrive30i M Sport"));
        assertThat(inquiryService.changeStatus(inquiry.id(), Inquiry.Status.CONTACTED).status())
                .isEqualTo(Inquiry.Status.CONTACTED);

        carService.delete(draft.id());
        assertThatThrownBy(() -> carService.getAdmin(draft.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void editingAPriceProfileRepricesItsCars() {
        String url = "https://www.autowini.com/test/" + UUID.randomUUID();
        var imported = new ImportedCar("IC1", null, CarSpecs.builder().title("Kia K5 2021").build(),
                new BigDecimal("25000"), "EUR", List.of(), List.of());
        AdminCar car = carService.createDraft(imported, CarSource.AUTOWINI, url);
        // 25000 + 5000 + 2 * 600 (15000 above 10000 = 2 started steps) + 2000
        assertThat(car.price().totalEur()).isEqualByComparingTo("33200");

        PricingProfile original = carService.pricingProfiles().stream()
                .filter(p -> p.id() == PriceProfile.AUTOWINI).findFirst().orElseThrow();
        try {
            carService.updatePricingProfile(PriceProfile.AUTOWINI, new PricingProfile(null,
                    new BigDecimal("5100"), new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("600"),
                    StepMode.PROPORTIONAL, new BigDecimal("2500"), null));
            // 25000 + 5100 + 1.5 * 600 + 2500
            assertThat(carService.getAdmin(car.id()).price().totalEur()).isEqualByComparingTo("33500");
        } finally {
            carService.updatePricingProfile(PriceProfile.AUTOWINI, original);
            carService.delete(car.id());
        }
    }

    @Test
    void recalculatesAllPrices() {
        assertThat(carService.recalculateAllPrices()).isGreaterThanOrEqualTo(3);
    }
}
