package com.premiumcars.catalog.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.premiumcars.catalog.domain.CarSort;
import com.premiumcars.catalog.domain.CarStatus;
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
        var filter = new CarFilter("Tucson", "Hyundai", FuelType.PETROL, null, Origin.KR, 2018, null,
                null, new BigDecimal("30000"), 100_000);

        var sql = CarSearchSql.of(filter, PUBLIC).page(CarSort.PRICE_ASC, 2, 12);

        assertThat(sql.text())
                .contains("c.title ILIKE :q", "lower(c.make) = :make", "c.fuel_type = :fuelType",
                        "c.source IN (:sources)", "c.model_year >= :yearFrom", "c.total_price_eur <= :priceTo",
                        "c.mileage_km <= :mileageTo", "ORDER BY c.total_price_eur ASC NULLS LAST",
                        "LIMIT :limit OFFSET :offset")
                .doesNotContain("Tucson", "Hyundai", "30000");
        assertThat(sql.params())
                .containsEntry("q", "%Tucson%")
                .containsEntry("make", "hyundai")
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
        var filter = new CarFilter("IC5546178", null, null, null, null, null, null, null, null, null);

        var admin = CarSearchSql.forAdmin(filter, EnumSet.allOf(CarStatus.class)).count();
        var pub = CarSearchSql.of(filter, PUBLIC).count();

        assertThat(admin.text()).contains("c.source_url ILIKE :q", "c.source_ref ILIKE :q");
        assertThat(admin.params()).containsEntry("q", "%IC5546178%");
        assertThat(pub.text()).doesNotContain("source_url").doesNotContain("source_ref");
    }
}
