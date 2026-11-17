package com.premiumcars.seo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.domain.PricingInputs;
import com.premiumcars.catalog.repo.CarRepository;
import com.premiumcars.catalog.service.CarService;
import com.premiumcars.catalog.web.dto.CarFilter;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.common.NotFoundException;
import com.premiumcars.seo.SeoDtos.MakeEntry;
import com.premiumcars.seo.SeoDtos.SavePageRequest;
import com.premiumcars.seo.SeoDtos.SeoPage;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SeoPagesDbTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String MAKE = "Zzseomake Q9";
    private static final String MAKE_SLUG = "zzseomake-q9";

    @Autowired
    SeoService seo;

    @Autowired
    SeoRepository seoRepository;

    @Autowired
    CarRepository cars;

    @Autowired
    CarService carService;

    private long insert(List<Long> ids, CarStatus status, CarSource source, String make, String model, Integer year,
                        FuelType fuel, String price) {
        CarSpecs specs = CarSpecs.builder().title(make + " " + model).make(make).model(model).year(year)
                .fuelType(fuel).build();
        String key = UUID.randomUUID().toString();
        long id = cars.insert("zzseo-" + key, status, source, "https://example.com/" + key, key, null, specs,
                PricingInputs.EMPTY, new BigDecimal(price));
        ids.add(id);
        return id;
    }

    private MakeEntry entry() {
        return seo.makes().stream().filter(m -> m.slug().equals(MAKE_SLUG)).findFirst().orElseThrow();
    }

    @Test
    void testMakes_PublicCarsOfOneMake_AggregatesAndMergesSpellings() {
        List<Long> ids = new ArrayList<>();
        try {
            insert(ids, CarStatus.PUBLISHED, CarSource.AUTOWINI, MAKE, "Alpha One", 2020, FuelType.PETROL, "10000");
            insert(ids, CarStatus.RESERVED, CarSource.AUTOWINI, MAKE, "Alpha One", 2022, FuelType.PETROL, "20000");
            insert(ids, CarStatus.PUBLISHED, CarSource.AUTOHOME, MAKE, "Alpha-One", 2019, FuelType.ELECTRIC, "30000");
            insert(ids, CarStatus.PUBLISHED, CarSource.AUTOWINI, MAKE, null, 2021, FuelType.PETROL, "12000");
            insert(ids, CarStatus.PUBLISHED, CarSource.AUTOWINI, "ZZSEOMAKE Q9", "Beta", 2021, FuelType.PETROL, "15000");
            insert(ids, CarStatus.DRAFT, CarSource.AUTOWINI, MAKE, "Alpha One", 2000, FuelType.DIESEL, "1");

            MakeEntry make = entry();
            assertThat(make.make()).isEqualTo(MAKE);
            assertThat(make.count()).isEqualTo(5);
            assertThat(make.minPriceEur()).isEqualByComparingTo("10000");
            assertThat(make.maxPriceEur()).isEqualByComparingTo("30000");
            assertThat(make.yearFrom()).isEqualTo(2019);
            assertThat(make.yearTo()).isEqualTo(2022);
            assertThat(make.origins()).containsExactlyInAnyOrder(Origin.KR, Origin.CN);
            assertThat(make.models()).hasSize(2);
            assertThat(make.models().get(0).model()).isEqualTo("Alpha One");
            assertThat(make.models().get(0).slug()).isEqualTo("alpha-one");
            assertThat(make.models().get(0).count()).isEqualTo(3);
            assertThat(make.models().get(0).minPriceEur()).isEqualByComparingTo("10000");
            assertThat(make.models().get(0).maxPriceEur()).isEqualByComparingTo("30000");
            assertThat(make.models().get(0).yearFrom()).isEqualTo(2019);
            assertThat(make.models().get(0).yearTo()).isEqualTo(2022);
            assertThat(make.models().get(1).slug()).isEqualTo("beta");
            assertThat(make.models().get(1).count()).isEqualTo(1);
        } finally {
            ids.forEach(carService::delete);
        }
    }

    @Test
    void testPages_MakeAndModelSlugs_ReturnNamesAndMatchTheSearch() {
        List<Long> ids = new ArrayList<>();
        try {
            insert(ids, CarStatus.PUBLISHED, CarSource.AUTOWINI, MAKE, "Alpha One", 2020, FuelType.PETROL, "10000");
            insert(ids, CarStatus.PUBLISHED, CarSource.AUTOWINI, MAKE, "Alpha One", 2022, FuelType.PETROL, "20000");
            insert(ids, CarStatus.PUBLISHED, CarSource.AUTOHOME, MAKE, "Alpha-One", 2019, FuelType.ELECTRIC, "30000");
            insert(ids, CarStatus.PUBLISHED, CarSource.AUTOWINI, "ZZSEOMAKE Q9", "Beta", 2021, FuelType.PETROL, "15000");
            insert(ids, CarStatus.DRAFT, CarSource.AUTOWINI, MAKE, "Alpha One", 2000, FuelType.DIESEL, "1");

            SeoPage makePage = seo.makePage(MAKE_SLUG);
            assertThat(makePage.model()).isNull();
            assertThat(makePage.modelSlug()).isNull();
            assertThat(makePage.make()).isEqualTo(MAKE);
            assertThat(makePage.makeNames()).containsExactly(MAKE, "ZZSEOMAKE Q9");
            assertThat(makePage.modelNames()).isEmpty();
            assertThat(makePage.count()).isEqualTo(4);
            assertThat(makePage.fuels()).containsExactly(FuelType.PETROL, FuelType.ELECTRIC);
            assertThat(makePage.models()).hasSize(2);
            assertThat(makePage.title()).isNull();

            SeoPage modelPage = seo.modelPage(MAKE_SLUG, "alpha-one");
            assertThat(modelPage.model()).isEqualTo("Alpha One");
            assertThat(modelPage.modelNames()).containsExactly("Alpha One", "Alpha-One");
            assertThat(modelPage.count()).isEqualTo(3);
            assertThat(modelPage.models()).isEmpty();
            assertThat(modelPage.fuels()).containsExactly(FuelType.PETROL, FuelType.ELECTRIC);

            var makeFilter = new CarFilter(null, null, null, null, null, null, null, null, null, null, null)
                    .withNames(makePage.makeNames(), null);
            assertThat(carService.searchPublic(makeFilter, null, 0, 48).totalItems()).isEqualTo(4);
            var modelFilter = new CarFilter(null, null, null, null, null, null, null, null, null, null, null)
                    .withNames(modelPage.makeNames(), modelPage.modelNames());
            assertThat(carService.searchPublic(modelFilter, null, 0, 48).totalItems()).isEqualTo(3);

            assertThatThrownBy(() -> seo.makePage("zzseo-unknown")).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> seo.modelPage(MAKE_SLUG, "zzseo-unknown")).isInstanceOf(NotFoundException.class);
        } finally {
            ids.forEach(carService::delete);
        }
    }

    @Test
    void testMakes_OnlyDraftCars_MakeIsNotListed() {
        List<Long> ids = new ArrayList<>();
        try {
            insert(ids, CarStatus.DRAFT, CarSource.AUTOWINI, "Zzseodraft Q8", "One", 2020, FuelType.PETROL, "10");
            assertThat(seo.makes()).noneMatch(m -> m.slug().equals("zzseodraft-q8"));
            assertThatThrownBy(() -> seo.makePage("zzseodraft-q8")).isInstanceOf(NotFoundException.class);
        } finally {
            ids.forEach(carService::delete);
        }
    }

    @Test
    void testSave_OverrideLifecycle_SavesReadsAndDeletes() {
        List<Long> ids = new ArrayList<>();
        try {
            insert(ids, CarStatus.PUBLISHED, CarSource.AUTOWINI, MAKE, "Alpha One", 2020, FuelType.PETROL, "10000");

            var saved = seo.save(new SavePageRequest(MAKE_SLUG, "alpha-one", "  Title  ", " ", " Body text "), "tester");
            assertThat(saved.title()).isEqualTo("Title");
            assertThat(saved.description()).isNull();
            assertThat(saved.text()).isEqualTo("Body text");
            assertThat(saved.count()).isEqualTo(1);
            assertThat(saved.updatedAt()).isNotNull();
            assertThat(seoRepository.find(MAKE_SLUG, "alpha-one").orElseThrow().updatedBy()).isEqualTo("tester");

            SeoPage page = seo.modelPage(MAKE_SLUG, "alpha-one");
            assertThat(page.title()).isEqualTo("Title");
            assertThat(page.text()).isEqualTo("Body text");
            assertThat(seo.makePage(MAKE_SLUG).title()).isNull();

            seo.save(new SavePageRequest(MAKE_SLUG, null, "Make title", "Make description", null), "tester");
            var rows = seo.adminPages().stream().filter(p -> MAKE_SLUG.equals(p.makeSlug())).toList();
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).modelSlug()).isNull();
            assertThat(rows.get(0).title()).isEqualTo("Make title");
            assertThat(rows.get(1).modelSlug()).isEqualTo("alpha-one");

            var deleted = seo.save(new SavePageRequest(MAKE_SLUG, "alpha-one", " ", null, ""), "tester");
            assertThat(deleted.title()).isNull();
            assertThat(deleted.updatedAt()).isNull();
            assertThat(seoRepository.find(MAKE_SLUG, "alpha-one")).isEmpty();
            assertThat(seo.modelPage(MAKE_SLUG, "alpha-one").title()).isNull();

            assertThatThrownBy(() -> seo.save(new SavePageRequest(MAKE_SLUG, null, "x".repeat(161), null, null), "t"))
                    .isInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> seo.save(new SavePageRequest("Bad Slug", null, "x", null, null), "t"))
                    .isInstanceOf(BadRequestException.class);
        } finally {
            seoRepository.delete(MAKE_SLUG, "alpha-one");
            seoRepository.delete(MAKE_SLUG, "");
            ids.forEach(carService::delete);
        }
    }

    @Test
    void testAdminPages_OverrideWithoutPublicCars_IsKeptWithCountZero() {
        try {
            seo.save(new SavePageRequest("zzseogone-q7", "model-x", "Gone", null, null), "tester");
            var row = seo.adminPages().stream()
                    .filter(p -> "zzseogone-q7".equals(p.makeSlug())).findFirst().orElseThrow();
            assertThat(row.count()).isZero();
            assertThat(row.modelSlug()).isEqualTo("model-x");
            assertThat(row.title()).isEqualTo("Gone");
        } finally {
            seoRepository.delete("zzseogone-q7", "model-x");
        }
    }
}
