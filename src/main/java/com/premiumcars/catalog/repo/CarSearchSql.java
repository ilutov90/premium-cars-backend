package com.premiumcars.catalog.repo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.premiumcars.catalog.domain.CarSort;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.web.dto.CarFilter;

/**
 * Builds the catalog search SQL from optional filters. Plain Java (no Spring), so it can be unit-tested
 * and its output pasted into psql. Only fixed SQL fragments are concatenated; every user value is a
 * named parameter.
 */
final class CarSearchSql {

    /** Columns of {@code car} read by {@link CarRowMapper}, prefixed with the "c" alias. */
    static final String CAR_COLUMNS = """
            c.id, c.slug, c.status, c.source, c.source_url, c.source_ref, c.source_location,
            c.title, c.make, c.model, c.trim_level, c.model_year, c.first_registration, c.mileage_km,
            c.fuel_type, c.transmission, c.drivetrain, c.engine_cc, c.engine, c.power_hp,
            c.battery_kwh, c.electric_range_km, c.range_standard, c.motor_power_kw, c.euro_standard,
            c.body_type, c.seats, c.doors, c.color, c.curb_weight_kg, c.dimensions, c.description, c.specs, c.features,
            c.spec_sheet,
            c.source_price, c.source_currency, c.price_profile, c.profit_eur, c.fixed_total_eur,
            c.total_price_eur, c.created_at, c.updated_at, c.published_at""";

    /** A SQL text with its named parameters (":name"). */
    record Sql(String text, Map<String, Object> params) {
    }

    private final StringBuilder where = new StringBuilder();
    private final Map<String, Object> params = new LinkedHashMap<>();
    private final boolean matchSource;

    private CarSearchSql(boolean matchSource) {
        this.matchSource = matchSource;
    }

    /** The public catalog: the text search looks at title, make and model. */
    static CarSearchSql of(CarFilter filter, Collection<CarStatus> statuses) {
        return build(filter, statuses, false);
    }

    /**
     * The admin list: the text search also matches the source listing's URL and id, so pasting a link from
     * autohome / autowini finds the car imported from it. Never used for public requests.
     */
    static CarSearchSql forAdmin(CarFilter filter, Collection<CarStatus> statuses) {
        return build(filter, statuses, true);
    }

    private static CarSearchSql build(CarFilter filter, Collection<CarStatus> statuses, boolean matchSource) {
        var b = new CarSearchSql(matchSource);
        b.where.append("WHERE c.status IN (:statuses)");
        b.params.put("statuses", statuses.stream().map(Enum::name).toList());
        if (filter != null) {
            b.addFilters(filter);
        }
        return b;
    }

    private void addFilters(CarFilter f) {
        if (notBlank(f.q())) {
            String fields = matchSource
                    ? "(c.title ILIKE :q OR c.make ILIKE :q OR c.model ILIKE :q"
                      + " OR c.source_url ILIKE :q OR c.source_ref ILIKE :q)"
                    : "(c.title ILIKE :q OR c.make ILIKE :q OR c.model ILIKE :q)";
            and(fields, "q", "%" + escapeLike(f.q().trim()) + "%");
        }
        if (notBlank(f.make())) {
            and("lower(c.make) = :make", "make", f.make().trim().toLowerCase(Locale.ROOT));
        }
        if (f.fuelType() != null) {
            and("c.fuel_type = :fuelType", "fuelType", f.fuelType().name());
        }
        if (f.transmission() != null) {
            and("c.transmission = :transmission", "transmission", f.transmission().name());
        }
        if (f.origin() != null) {
            and("c.source = :source", "source", f.origin().source().name());
        }
        if (f.yearFrom() != null) {
            and("c.model_year >= :yearFrom", "yearFrom", f.yearFrom());
        }
        if (f.yearTo() != null) {
            and("c.model_year <= :yearTo", "yearTo", f.yearTo());
        }
        if (f.priceFrom() != null) {
            and("c.total_price_eur >= :priceFrom", "priceFrom", f.priceFrom());
        }
        if (f.priceTo() != null) {
            and("c.total_price_eur <= :priceTo", "priceTo", f.priceTo());
        }
        if (f.mileageTo() != null) {
            and("c.mileage_km <= :mileageTo", "mileageTo", f.mileageTo());
        }
    }

    Sql count() {
        return new Sql("SELECT count(*) FROM car c " + where, params);
    }

    /** One page of cars, each with the storage path of its cover photo (or null). */
    Sql page(CarSort sort, int page, int size) {
        var p = new LinkedHashMap<>(params);
        p.put("limit", size);
        p.put("offset", page * size);
        String text = "SELECT " + CAR_COLUMNS + ", cover.storage_path AS cover_path\n"
                + "FROM car c\n"
                + "LEFT JOIN LATERAL (SELECT i.storage_path FROM car_image i WHERE i.car_id = c.id\n"
                + "                   ORDER BY i.position LIMIT 1) cover ON true\n"
                + where + "\n"
                + "ORDER BY " + sort.orderBy() + "\n"
                + "LIMIT :limit OFFSET :offset";
        return new Sql(text, p);
    }

    private void and(String condition, String name, Object value) {
        where.append(" AND ").append(condition);
        params.put(name, value);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Treat % and _ typed by the user as literal characters (PostgreSQL's default LIKE escape is \). */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static List<String> names(Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }
}
