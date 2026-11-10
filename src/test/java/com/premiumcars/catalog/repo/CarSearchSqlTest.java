package com.premiumcars.catalog.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.catalog.domain.CarSort;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.PostedFilter;
import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Origin;
import com.premiumcars.catalog.web.dto.CarFilter;

/** Fast checks of the generated SQL text (no database). The SQL itself is exercised in JdbcRepositoriesTest. */
class CarSearchSqlTest {

    private static final EnumSet<CarStatus> PUBLIC = EnumSet.of(CarStatus.PUBLISHED, CarStatus.RESERVED);

    @Test
    void noFiltersOnlyRestrictsStatus() {
        var sql = CarSearchSql.of(null, PUBLIC).count();

        assertThat(sql.text()).isEqualTo("SELECT count(*) FROM car c WHERE c.status IN (:statuses)");
        assertThat(sql.params()).containsEntry("statuses", List.of("PUBLISHED", "RESERVED")).hasSize(1);
    }

    @Test
    void everyFilterBecomesANamedParameter() {
        var filter = new CarFilter("Tucson", "Hyundai", "Tucson", FuelType.PETROL, null, Origin.KR, 2018, null,
                null, new BigDecimal("30000"), 100_000);

        var sql = CarSearchSql.of(filter, PUBLIC).page(CarSort.PRICE_ASC, 2, 12);

        assertThat(sql.text())
                .contains("c.title ILIKE :q", "lower(c.make) = :make", "lower(c.model) = :model", "c.fuel_type = :fuelType",
                        "c.source IN (:sources)", "c.model_year >= :yearFrom", "c.total_price_eur <= :priceTo",
                        "c.mileage_km <= :mileageTo", "ORDER BY c.total_price_eur ASC NULLS LAST",
                        "LIMIT :limit OFFSET :offset")
                .doesNotContain("Tucson", "Hyundai", "30000");
        assertThat(sql.params())
                .containsEntry("q", "%Tucson%")
                .containsEntry("make", "hyundai")
                .containsEntry("model", "tucson")
                .containsEntry("sources", List.of("AUTOWINI"))
                .containsEntry("limit", 12)
                .containsEntry("offset", 24);
    }

    @Test
    void userWildcardsAreLiteral() {
        assertThat(CarSearchSql.escapeLike("50%_off\\")).isEqualTo("50\\%\\_off\\\\");
    }

    @Test
    void unknownSortFallsBackToNewest() {
        assertThat(CarSort.fromParam("'; DROP TABLE car; --")).isEqualTo(CarSort.NEWEST);
        assertThat(CarSort.fromParam(null)).isEqualTo(CarSort.NEWEST);
        assertThat(CarSort.fromParam("price_desc")).isEqualTo(CarSort.PRICE_DESC);
    }

    @Test
    void onlyTheAdminSearchMatchesTheSourceListing() {
        var filter = new CarFilter("IC5546178", null, null, null, null, null, null, null, null, null, null);

        var admin = CarSearchSql.forAdmin(filter, EnumSet.allOf(CarStatus.class)).count();
        var pub = CarSearchSql.of(filter, PUBLIC).count();

        assertThat(admin.text()).contains("c.source_url ILIKE :q", "c.source_ref ILIKE :q");
        assertThat(admin.params()).containsEntry("q", "%IC5546178%");
        assertThat(pub.text()).doesNotContain("source_url").doesNotContain("source_ref");
    }

    @Test
    void theAdminListFiltersBySourceAndCountsPerStatus() {
        var sql = CarSearchSql.forAdmin(null, EnumSet.allOf(CarStatus.class), CarSource.GUAZI);
        assertThat(sql.count().text()).contains("c.source = :source");
        assertThat(sql.count().params()).containsEntry("source", "GUAZI");
        assertThat(sql.countByStatus().text()).startsWith("SELECT c.status, count(*) AS n FROM car c WHERE")
                .endsWith("GROUP BY c.status");
        assertThat(CarSearchSql.forAdmin(null, EnumSet.allOf(CarStatus.class), null).count().text())
                .doesNotContain(":source");
        assertThat(sql.page(CarSort.fromAdminParam("created"), 0, 48).text()).contains("ORDER BY c.created_at DESC");
        assertThat(CarSort.fromAdminParam("price_desc")).isEqualTo(CarSort.PRICE_DESC);
        assertThat(CarSort.fromAdminParam("nonsense")).isEqualTo(CarSort.UPDATED);
        assertThat(CarSort.fromAdminParam(null)).isEqualTo(CarSort.UPDATED);
    }

    @Test
    void testSiteSlug_LinkToACarPageOnOurSite_ReturnsTheSlug() {
        assertThat(CarSearchSql.siteSlug("https://premiumcarsimport.bg/cars/kia-sportage-2021-29538?utm_source=fb"))
                .isEqualTo("kia-sportage-2021-29538");
        assertThat(CarSearchSql.siteSlug("premiumcarsimport.bg/cars/bmw-520d-2019")).isEqualTo("bmw-520d-2019");
        assertThat(CarSearchSql.siteSlug("Kia Sportage")).isNull();
    }

    @Test
    void testCarNumber_NumberAloneWithOrWithoutSign_ReturnsTheNumber() {
        assertThat(CarSearchSql.carNumber("29538")).isEqualTo(29538L);
        assertThat(CarSearchSql.carNumber("#29538")).isEqualTo(29538L);
        assertThat(CarSearchSql.carNumber(" № 29538 ")).isEqualTo(29538L);
        assertThat(CarSearchSql.carNumber("IC5546178")).isNull();
        assertThat(CarSearchSql.carNumber("Kia 2021")).isNull();
    }

    @Test
    void testForAdmin_SiteLinkOrNumberAsText_MatchesThatCarOnly() {
        var byLink = CarSearchSql.forAdmin(text("https://premiumcarsimport.bg/cars/kia-sportage-2021"),
                EnumSet.allOf(CarStatus.class)).count();
        assertThat(byLink.text()).contains("c.slug = :slug");
        assertThat(byLink.params()).containsEntry("slug", "kia-sportage-2021");

        var byNumber = CarSearchSql.forAdmin(text("№ 29538"), EnumSet.allOf(CarStatus.class)).count();
        assertThat(byNumber.text()).contains("c.id = :carNumber OR c.source_ref ILIKE :q").doesNotContain("c.title");
        assertThat(byNumber.params()).containsEntry("carNumber", 29538L).containsEntry("q", "%29538%");
    }

    @Test
    void testForAdmin_PostedFilter_AddsItsFixedCondition() {
        var none = CarSearchSql.forAdmin(null, EnumSet.allOf(CarStatus.class), null, PostedFilter.NONE);
        assertThat(none.count().text()).contains("NOT (EXISTS (SELECT 1 FROM social_post");
        assertThat(none.countByStatus().text()).contains("mobile_bg_listing").contains("cars_bg_listing");

        var notMobile = CarSearchSql.forAdmin(null, EnumSet.allOf(CarStatus.class), null, PostedFilter.NOT_MOBILE_BG);
        assertThat(notMobile.count().text()).contains("NOT EXISTS (SELECT 1 FROM mobile_bg_listing");
        assertThat(PostedFilter.fromParam("not_facebook")).isEqualTo(PostedFilter.NOT_FACEBOOK);
        assertThat(PostedFilter.fromParam("'; DROP TABLE car; --")).isNull();
        assertThat(PostedFilter.fromParam(null)).isNull();
    }

    private static CarFilter text(String q) {
        return new CarFilter(q, null, null, null, null, null, null, null, null, null, null);
    }
}
