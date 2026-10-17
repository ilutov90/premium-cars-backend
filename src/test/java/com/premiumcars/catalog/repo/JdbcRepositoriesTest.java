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
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.NewCarImage;
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
        var imported = new ImportedCar("123456", "BMW X3 2022 xDrive30i", "BMW", "X3", null, 2022, 30_000,
                new BigDecimal("20000"), "USD", null, Transmission.AUTOMATIC, null, null, null, null,
                Map.of("Drive", "AWD"), List.of());

        AdminCar draft = carService.createDraft(imported, CarSource.AUTOHOME, url);
        assertThat(draft.status()).isEqualTo(CarStatus.DRAFT);
        assertThat(draft.slug()).isEqualTo("bmw-x3-2022-" + draft.id());
        assertThat(draft.specs()).containsEntry("Drive", "AWD");
        assertThat(draft.price()).isNotNull();
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

        var update = new CarUpdateRequest("BMW X3 xDrive30i M Sport", "BMW", "X3", "M Sport", 2022, 31_000,
                FuelType.PETROL, Transmission.AUTOMATIC, "SUV", "2.0T", 245, "Black", "Description",
                Map.of("Drive", "AWD", "Seats", "5"), new BigDecimal("21000"), "usd", null,
                new BigDecimal("2000"), new BigDecimal("0.05"));
        AdminCar updated = carService.update(draft.id(), update);
        assertThat(updated.sourceCurrency()).isEqualTo("USD");
        assertThat(updated.extraDutyRate()).isEqualByComparingTo("0.05");
        assertThat(updated.slug()).isEqualTo("bmw-x3-2022-" + draft.id());

        AdminCar published = carService.changeStatus(draft.id(), CarStatus.PUBLISHED);
        assertThat(published.publishedAt()).isNotNull();
        assertThat(carService.getPublic(published.slug()).imageUrls()).hasSize(2);
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
    void recalculatesAllPrices() {
        assertThat(carService.recalculateAllPrices()).isGreaterThanOrEqualTo(3);
    }
}
