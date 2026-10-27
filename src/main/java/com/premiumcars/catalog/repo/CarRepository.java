package com.premiumcars.catalog.repo;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.catalog.domain.Car;
import com.premiumcars.catalog.domain.CarSort;
import com.premiumcars.catalog.domain.CarSpecs;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.catalog.domain.CarStatus;
import com.premiumcars.catalog.domain.PricingInputs;
import com.premiumcars.catalog.web.dto.CarFilter;
import com.premiumcars.pricing.PriceBreakdown;

/** All SQL for the {@code car} table. */
@Repository
public class CarRepository {

    /**
     * A car in a list, with its cover photo: the storage path, or for a linked photo null and its URL (both null when
     * the car has no photos).
     */
    public record CarListRow(Car car, String coverPath, String coverUrl) {
    }

    public record CarPage(List<CarListRow> rows, long total) {
    }

    private static final String SELECT_CAR = "SELECT " + CarSearchSql.CAR_COLUMNS + " FROM car c ";

    private final JdbcClient jdbc;
    private final JsonColumns json;
    private final CarRowMapper carMapper;
    private final RowMapper<CarListRow> listRowMapper;

    public CarRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
        this.carMapper = new CarRowMapper(json);
        this.listRowMapper = (rs, n) -> new CarListRow(carMapper.mapRow(rs, n), rs.getString("cover_path"),
                rs.getString("cover_url"));
    }

    // ---------------------------------------------------------------- queries

    public Optional<Car> findById(long id) {
        return jdbc.sql(SELECT_CAR + "WHERE c.id = :id")
                .param("id", id)
                .query(carMapper)
                .optional();
    }

    public Optional<Car> findBySlug(String slug) {
        return jdbc.sql(SELECT_CAR + "WHERE c.slug = :slug")
                .param("slug", slug)
                .query(carMapper)
                .optional();
    }

    public Optional<Car> findBySourceUrl(String sourceUrl) {
        return jdbc.sql(SELECT_CAR + "WHERE c.source_url = :sourceUrl")
                .param("sourceUrl", sourceUrl)
                .query(carMapper)
                .optional();
    }

    public List<Car> findAll() {
        return jdbc.sql(SELECT_CAR + "ORDER BY c.id").query(carMapper).list();
    }

    public CarPage search(CarFilter filter, Collection<CarStatus> statuses, CarSort sort, int page, int size) {
        return search(CarSearchSql.of(filter, statuses), sort, page, size);
    }

    /** Like {@link #search}, but the text also matches the source listing's URL and id (admin only). */
    public CarPage searchAdmin(CarFilter filter, Collection<CarStatus> statuses, CarSort sort, int page, int size) {
        return search(CarSearchSql.forAdmin(filter, statuses), sort, page, size);
    }

    private CarPage search(CarSearchSql sql, CarSort sort, int page, int size) {
        CarSearchSql.Sql count = sql.count();
        long total = jdbc.sql(count.text()).params(count.params()).query(Long.class).single();
        if (total == 0) {
            return new CarPage(List.of(), 0);
        }
        CarSearchSql.Sql pageSql = sql.page(sort, page, size);
        List<CarListRow> rows = jdbc.sql(pageSql.text()).params(pageSql.params()).query(listRowMapper).list();
        return new CarPage(rows, total);
    }

    public List<String> findMakes(Collection<CarStatus> statuses) {
        return jdbc.sql("""
                        SELECT DISTINCT make FROM car
                        WHERE status IN (:statuses) AND make IS NOT NULL
                        ORDER BY make""")
                .param("statuses", CarSearchSql.names(statuses))
                .query(String.class)
                .list();
    }

    // ---------------------------------------------------------------- writes

    /** Inserts a car and returns its generated id. */
    public long insert(String slug, CarStatus status, CarSource source, String sourceUrl, String sourceRef,
                       String sourceLocation, CarSpecs specs, PricingInputs pricing, BigDecimal totalPriceEur) {
        Map<String, Object> p = specParams(specs, pricing);
        p.put("slug", slug);
        p.put("status", status.name());
        p.put("source", source.name());
        p.put("sourceUrl", sourceUrl);
        p.put("sourceRef", sourceRef);
        p.put("sourceLocation", sourceLocation);
        p.put("totalPriceEur", totalPriceEur);
        return jdbc.sql("""
                        INSERT INTO car (slug, status, source, source_url, source_ref, source_location,
                                         title, make, model, trim_level, model_year, first_registration, mileage_km,
                                         fuel_type, transmission, drivetrain, engine_cc, engine, power_hp,
                                         battery_kwh, electric_range_km, range_standard, motor_power_kw,
                                         battery_health_pct, euro_standard,
                                         body_type, seats, doors, color, curb_weight_kg, dimensions,
                                         description, specs, features, spec_sheet,
                                         source_price, source_currency, price_profile, profit_eur, fixed_total_eur,
                                         total_price_eur, created_at, updated_at)
                        VALUES (:slug, :status, :source, :sourceUrl, :sourceRef, :sourceLocation,
                                :title, :make, :model, :trim, :year, :firstRegistration, :mileageKm,
                                :fuelType, :transmission, :drivetrain, :engineCc, :engine, :powerHp,
                                :batteryKwh, :electricRangeKm, :rangeStandard, :motorPowerKw,
                                :batteryHealthPct, :euroStandard,
                                :bodyType, :seats, :doors, :color, :curbWeightKg, :dimensions,
                                :description, CAST(:specs AS jsonb), CAST(:features AS jsonb),
                                CAST(:sheet AS jsonb),
                                :sourcePrice, :sourceCurrency, :priceProfile, :profitEur, :fixedTotalEur,
                                :totalPriceEur, now(), now())
                        RETURNING id""")
                .params(p)
                .query(Long.class)
                .single();
    }

    /**
     * Replaces all editable fields of a car (not the imported spec sheet, which stays as it was). Returns false
     * when the car does not exist.
     */
    public boolean update(long id, CarSpecs specs, PricingInputs pricing, BigDecimal totalPriceEur) {
        Map<String, Object> p = specParams(specs, pricing);
        p.put("id", id);
        p.put("totalPriceEur", totalPriceEur);
        int rows = jdbc.sql("""
                        UPDATE car SET
                            title = :title, make = :make, model = :model, trim_level = :trim,
                            model_year = :year, first_registration = :firstRegistration, mileage_km = :mileageKm,
                            fuel_type = :fuelType, transmission = :transmission, drivetrain = :drivetrain,
                            engine_cc = :engineCc, engine = :engine, power_hp = :powerHp,
                            battery_kwh = :batteryKwh, electric_range_km = :electricRangeKm,
                            range_standard = :rangeStandard, motor_power_kw = :motorPowerKw,
                            battery_health_pct = :batteryHealthPct, euro_standard = :euroStandard,
                            body_type = :bodyType, seats = :seats, doors = :doors, color = :color,
                            curb_weight_kg = :curbWeightKg, dimensions = :dimensions,
                            description = :description, specs = CAST(:specs AS jsonb),
                            features = CAST(:features AS jsonb),
                            source_price = :sourcePrice, source_currency = :sourceCurrency,
                            price_profile = :priceProfile, profit_eur = :profitEur,
                            fixed_total_eur = :fixedTotalEur,
                            total_price_eur = :totalPriceEur, updated_at = now()
                        WHERE id = :id""")
                .params(p)
                .update();
        return rows == 1;
    }

    public void updateSlug(long id, String slug) {
        jdbc.sql("UPDATE car SET slug = :slug WHERE id = :id")
                .param("slug", slug)
                .param("id", id)
                .update();
    }

    /** Sets the status; the first publish also stamps published_at, REMOVED stamps removed_at. */
    public void updateStatus(long id, CarStatus status) {
        jdbc.sql("""
                        UPDATE car SET
                            status = :status,
                            published_at = CASE WHEN :publish THEN COALESCE(published_at, now()) ELSE published_at END,
                            removed_at = CASE WHEN :removed THEN COALESCE(removed_at, now()) END,
                            updated_at = now()
                        WHERE id = :id""")
                .param("status", status.name())
                .param("publish", status == CarStatus.PUBLISHED)
                .param("removed", status == CarStatus.REMOVED)
                .param("id", id)
                .update();
    }

    /**
     * The listing disappeared from the source (automatic import): hides a PUBLISHED, RESERVED or DRAFT car; SOLD and
     * ARCHIVED cars stay as they are.
     *
     * @return false when the car was in another status
     */
    public boolean markRemoved(long id) {
        return jdbc.sql("""
                        UPDATE car SET status = 'REMOVED', removed_at = now(), updated_at = now()
                        WHERE id = :id AND status IN ('PUBLISHED', 'RESERVED', 'DRAFT')""")
                .param("id", id)
                .update() == 1;
    }

    /** The listing is back at the source: a REMOVED car is published again. @return false when it was not REMOVED */
    public boolean restoreRemoved(long id) {
        return jdbc.sql("""
                        UPDATE car SET status = 'PUBLISHED', removed_at = NULL,
                            published_at = COALESCE(published_at, now()), updated_at = now()
                        WHERE id = :id AND status = 'REMOVED'""")
                .param("id", id)
                .update() == 1;
    }

    /** REMOVED cars hidden before {@code before} (to delete). */
    public List<Long> findRemovedBefore(java.time.Instant before) {
        return jdbc.sql("SELECT id FROM car WHERE status = 'REMOVED' AND removed_at < :before ORDER BY id")
                .param("before", java.sql.Timestamp.from(before))
                .query(Long.class)
                .list();
    }

    /** The price and the breakdown it was calculated from (kept: see 013-frozen-prices.sql). */
    public void savePrice(long id, BigDecimal totalPriceEur, PriceBreakdown breakdown) {
        jdbc.sql("UPDATE car SET total_price_eur = :total, price_breakdown = CAST(:breakdown AS jsonb) WHERE id = :id")
                .param("total", totalPriceEur)
                .param("breakdown", json.write(breakdown))
                .param("id", id)
                .update();
    }

    public Optional<PriceBreakdown> findPriceBreakdown(long id) {
        return jdbc.sql("SELECT price_breakdown FROM car WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> Optional.ofNullable(json.read(rs.getString(1), PriceBreakdown.class)))
                .optional()
                .flatMap(o -> o);
    }

    /** Cars saved before prices were kept with their breakdown. */
    public List<Car> findWithoutPriceBreakdown() {
        return jdbc.sql(SELECT_CAR + "WHERE c.price_breakdown IS NULL ORDER BY c.id").query(carMapper).list();
    }

    /** Deletes the car; its images go with it (ON DELETE CASCADE), its inquiries keep a null car_id. */
    public void delete(long id) {
        jdbc.sql("DELETE FROM car WHERE id = :id").param("id", id).update();
    }

    // ---------------------------------------------------------------- helpers

    /** Named parameters shared by INSERT and UPDATE. A HashMap because values may be null. */
    private Map<String, Object> specParams(CarSpecs d, PricingInputs pricing) {
        Map<String, Object> p = new HashMap<>();
        p.put("title", d.title());
        p.put("make", d.make());
        p.put("model", d.model());
        p.put("trim", d.trim());
        p.put("year", d.year());
        p.put("firstRegistration", d.firstRegistration());
        p.put("mileageKm", d.mileageKm());
        p.put("fuelType", name(d.fuelType()));
        p.put("transmission", name(d.transmission()));
        p.put("drivetrain", name(d.drivetrain()));
        p.put("engineCc", d.engineCc());
        p.put("engine", d.engine());
        p.put("powerHp", d.powerHp());
        p.put("batteryKwh", d.batteryKwh());
        p.put("electricRangeKm", d.electricRangeKm());
        p.put("rangeStandard", d.rangeStandard());
        p.put("motorPowerKw", d.motorPowerKw());
        p.put("batteryHealthPct", d.batteryHealthPct());
        p.put("euroStandard", d.euroStandard());
        p.put("bodyType", d.bodyType());
        p.put("seats", d.seats());
        p.put("doors", d.doors());
        p.put("color", d.color());
        p.put("curbWeightKg", d.curbWeightKg());
        p.put("dimensions", d.dimensions());
        p.put("description", d.description());
        p.put("specs", json.write(d.extraSpecs()));
        p.put("features", json.write(d.features().stream().map(Enum::name).toList()));
        p.put("sheet", json.write(d.sheet()));
        p.put("sourcePrice", pricing.sourcePrice());
        p.put("sourceCurrency", pricing.sourceCurrency());
        p.put("priceProfile", name(pricing.profile()));
        p.put("profitEur", pricing.profitEur());
        p.put("fixedTotalEur", pricing.fixedTotalEur());
        return p;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
