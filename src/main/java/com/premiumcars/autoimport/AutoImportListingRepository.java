package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.catalog.domain.CarSource;

/** SQL for {@code auto_import_listing}: what the runs found and how far each listing got. */
@Repository
public class AutoImportListingRepository {

    /** To import (again). */
    public static final String QUEUED = "QUEUED";
    /** In our catalog: {@code carId}. */
    public static final String IMPORTED = "IMPORTED";
    /** Failed {@value #MAX_ATTEMPTS} times in a row in a run; the next run queues it again while the source has it. */
    public static final String FAILED = "FAILED";
    /** Over the mileage limit ({@code mileageKm}): not imported; queued again when the limit goes above it. */
    public static final String SKIPPED = "SKIPPED";
    /** Over the highest FOB price: queued again when the limit or its price allows it. */
    public static final String PRICE_SKIPPED = "PRICE_SKIPPED";
    /**
     * Its page showed no FOB price (a guazi auction): kept by its listing id and never read or queued again while
     * the source has it, not counted by the preview either.
     */
    public static final String NO_PRICE = "NO_PRICE";
    static final int MAX_ATTEMPTS = 3;

    public record Listing(CarSource source, String listingId, String url, String brandCode, String title,
                          BigDecimal price, String state, int attempts, String lastError, int missedPasses,
                          Long carId, Integer mileageKm, Instant updatedAt, Instant lastSeen, String model) {
    }

    private static final String COLUMNS = "source, listing_id, url, brand_code, title, price, state, attempts,"
            + " last_error, missed_passes, car_id, mileage_km, updated_at, last_seen, model";

    private static final RowMapper<Listing> MAPPER = (rs, n) -> new Listing(
            CarSource.valueOf(rs.getString("source")), rs.getString("listing_id"), rs.getString("url"),
            rs.getString("brand_code"), rs.getString("title"), rs.getBigDecimal("price"), rs.getString("state"),
            rs.getInt("attempts"), rs.getString("last_error"), rs.getInt("missed_passes"),
            rs.getObject("car_id", Long.class), rs.getObject("mileage_km", Integer.class),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
            rs.getObject("last_seen", OffsetDateTime.class).toInstant(),
            rs.getString("model"));

    private final JdbcClient jdbc;

    public AutoImportListingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Listing> find(CarSource source, String listingId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM auto_import_listing WHERE source = :source AND listing_id = :id")
                .param("source", source.name())
                .param("id", listingId)
                .query(MAPPER)
                .optional();
    }

    public void insert(CarSource source, FoundListing l, String brandCode, String state, Long carId) {
        jdbc.sql("""
                        INSERT INTO auto_import_listing
                            (source, listing_id, url, brand_code, title, price, state, car_id, mileage_km, model)
                        VALUES (:source, :id, :url, :brand, :title, :price, :state, :carId, :km, :model)""")
                .param("model", cut(l.model(), 100), Types.VARCHAR)
                .param("source", source.name())
                .param("id", l.id())
                .param("url", l.url())
                .param("brand", brandCode)
                .param("title", cut(l.title(), 300), Types.VARCHAR)
                .param("price", l.price(), Types.NUMERIC)
                .param("state", state)
                .param("carId", carId, Types.BIGINT)
                .param("km", l.mileageKm(), Types.INTEGER)
                .update();
    }

    /**
     * Seen again: stamps it and decides whether it waits for the import. A failed one is queued again (with
     * {@value #MAX_ATTEMPTS} new attempts); the limits are followed both ways: over the mileage limit ({@code maxKm},
     * null = none) SKIPPED, over the highest price ({@code overPrice}, worked out by the caller) PRICE_SKIPPED, else
     * queued. One without a FOB price on its page (NO_PRICE) stays so: it is not read again.
     */
    public void seen(CarSource source, FoundListing l, String brandCode, Integer maxKm, boolean overPrice) {
        String target = """
                CASE WHEN :maxKm > 0 AND COALESCE(:km, mileage_km) > :maxKm THEN 'SKIPPED'
                     WHEN :overPrice THEN 'PRICE_SKIPPED'
                     ELSE 'QUEUED' END""";
        jdbc.sql("""
                        UPDATE auto_import_listing SET
                            last_seen = now(), missed_passes = 0, brand_code = :brand, title = :title, price = :price,
                            mileage_km = COALESCE(:km, mileage_km), model = COALESCE(:model, model),
                            state = CASE
                                WHEN state IN ('QUEUED', 'SKIPPED', 'PRICE_SKIPPED', 'FAILED') THEN %1$s
                                ELSE state END,
                            attempts = CASE WHEN state = 'FAILED' THEN 0 ELSE attempts END,
                            updated_at = now()
                        WHERE source = :source AND listing_id = :id""".formatted(target))
                .param("km", l.mileageKm(), Types.INTEGER)
                .param("model", cut(l.model(), 100), Types.VARCHAR)
                .param("maxKm", maxKm == null ? 0 : maxKm)
                .param("overPrice", overPrice)
                .param("brand", brandCode)
                .param("title", cut(l.title(), 300), Types.VARCHAR)
                .param("price", l.price(), Types.NUMERIC)
                .param("source", source.name())
                .param("id", l.id())
                .update();
    }

    /** The page showed no FOB price: one more run without it. */
    public void noPrice(CarSource source, String listingId, String note) {
        jdbc.sql("""
                        UPDATE auto_import_listing SET state = 'NO_PRICE', no_price_count = no_price_count + 1,
                            attempts = 0, last_error = :note, updated_at = now()
                        WHERE source = :source AND listing_id = :id""")
                .param("note", cut(note, 1000), Types.VARCHAR)
                .param("source", source.name())
                .param("id", listingId)
                .update();
    }

    /** The site refused us, not the listing: it stays queued, no attempt counted. */
    public void retryLater(CarSource source, String listingId, String note) {
        jdbc.sql("""
                        UPDATE auto_import_listing SET state = 'QUEUED', last_error = :note, updated_at = now()
                        WHERE source = :source AND listing_id = :id""")
                .param("note", cut(note, 1000), Types.VARCHAR)
                .param("source", source.name())
                .param("id", listingId)
                .update();
    }

    /** Over the highest FOB price (found out at the import). */
    public void priceSkipped(CarSource source, String listingId, String note) {
        jdbc.sql("""
                        UPDATE auto_import_listing SET state = 'PRICE_SKIPPED', last_error = :note, updated_at = now()
                        WHERE source = :source AND listing_id = :id""")
                .param("note", cut(note, 1000), Types.VARCHAR)
                .param("source", source.name())
                .param("id", listingId)
                .update();
    }

    /** How many listings of each make were left out lately (mileage, price, no FOB price for good): for the preview. */
    public Map<String, Integer> leftOutByBrand(CarSource source) {
        Map<String, Integer> out = new HashMap<>();
        jdbc.sql("""
                        SELECT brand_code, count(*) AS n FROM auto_import_listing
                        WHERE source = :source AND last_seen > now() - INTERVAL '3 days'
                          AND state IN ('SKIPPED', 'PRICE_SKIPPED', 'NO_PRICE')
                        GROUP BY brand_code""")
                .param("source", source.name())
                .query((rs, n) -> out.put(rs.getString("brand_code"), rs.getInt("n")))
                .list();
        return out;
    }

    /**
     * Our cars of a source on the site or as drafts (not REMOVED, SOLD or ARCHIVED): the page's "Проверка на колите".
     */
    public record CatalogCheck(int cars) {
    }

    public CatalogCheck check(CarSource source) {
        return new CatalogCheck(count("""
                SELECT count(*) FROM car c WHERE c.source = :source AND c.status IN ('PUBLISHED', 'RESERVED', 'DRAFT')""",
                source));
    }

    private int count(String sql, CarSource source) {
        return jdbc.sql(sql).param("source", source.name()).query(Integer.class).single();
    }

    /** The listing rows of these cars point to {@code keep} instead (before the others are deleted). */
    public void relinkCars(Collection<Long> from, long keep) {
        if (from.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE auto_import_listing SET car_id = :keep WHERE car_id IN (:from)")
                .param("keep", keep)
                .param("from", List.copyOf(from))
                .update();
    }

    /** The cars the automatic import knows a listing of (whatever the make). */
    public Set<Long> carIds(CarSource source) {
        return new HashSet<>(jdbc.sql("""
                        SELECT car_id FROM auto_import_listing WHERE source = :source AND car_id IS NOT NULL""")
                .param("source", source.name())
                .query(Long.class)
                .list());
    }

    /** The source's listings of these makes. */
    public List<Listing> ofBrands(CarSource source, Collection<String> brandCodes) {
        if (brandCodes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM auto_import_listing WHERE source = :source AND brand_code IN (:brands)")
                .param("source", source.name())
                .param("brands", brandCodes)
                .query(MAPPER)
                .list();
    }

    public void missed(CarSource source, String listingId) {
        jdbc.sql("""
                        UPDATE auto_import_listing SET missed_passes = missed_passes + 1, updated_at = now()
                        WHERE source = :source AND listing_id = :id""")
                .param("source", source.name())
                .param("id", listingId)
                .update();
    }

    /** The listing rows of a car (before it is deleted because its listing is gone). */
    public void deleteOfCar(long carId) {
        jdbc.sql("DELETE FROM auto_import_listing WHERE car_id = :id").param("id", carId).update();
    }

    public void delete(CarSource source, String listingId) {
        jdbc.sql("DELETE FROM auto_import_listing WHERE source = :source AND listing_id = :id")
                .param("source", source.name())
                .param("id", listingId)
                .update();
    }

    /** The export's columns ({@link #export}), in order. */
    public static final List<String> EXPORT_COLUMNS = List.of("listing_id", "state", "brand_code", "title", "price",
            "mileage_km", "attempts", "no_price_count", "missed_passes", "last_error", "car_id", "car_status",
            "car_by_listing_id", "car_by_listing_id_status", "first_seen", "last_seen", "updated_at", "url");

    /**
     * Every listing row of the source with its car (by the row's link and by the listing id), for the admins'
     * download that explains what the runs queue. Rows go to {@code row} one by one, as text.
     */
    public void export(CarSource source, java.util.function.Consumer<List<String>> row) {
        jdbc.sql("""
                        SELECT l.listing_id, l.state, l.brand_code, l.title, l.price, l.mileage_km, l.attempts,
                               l.no_price_count, l.missed_passes, l.last_error, l.car_id, c.status AS car_status,
                               r.id AS ref_id, r.status AS ref_status, l.first_seen, l.last_seen, l.updated_at, l.url
                        FROM auto_import_listing l
                        LEFT JOIN car c ON c.id = l.car_id
                        LEFT JOIN (SELECT DISTINCT ON (lower(source_ref)) lower(source_ref) AS ref, id, status
                                   FROM car WHERE source = :source AND source_ref IS NOT NULL
                                   ORDER BY lower(source_ref), id DESC) r ON r.ref = lower(l.listing_id)
                        WHERE l.source = :source
                        ORDER BY l.first_seen DESC, l.listing_id""")
                .param("source", source.name())
                .query((rs, n) -> {
                    List<String> out = new java.util.ArrayList<>(EXPORT_COLUMNS.size());
                    for (int i = 1; i <= EXPORT_COLUMNS.size(); i++) {
                        Object v = rs.getObject(i);
                        out.add(v == null ? "" : v instanceof java.sql.Timestamp t ? t.toInstant().toString()
                                : v.toString());
                    }
                    row.accept(out);
                    return null;
                })
                .list();
    }

    public int count(CarSource source) {
        return jdbc.sql("SELECT count(*) FROM auto_import_listing WHERE source = :source")
                .param("source", source.name())
                .query(Integer.class)
                .single();
    }

    /**
     * Empties the source's queue: its listings waiting to be imported are forgotten (nothing else: imported, set
     * aside or failed ones stay). The next run finds those still at the source again and queues them as new.
     *
     * @return how many were taken out
     */
    public int clearQueue(CarSource source) {
        return jdbc.sql("DELETE FROM auto_import_listing WHERE source = :source AND state = 'QUEUED' AND car_id IS NULL")
                .param("source", source.name())
                .update();
    }

    /** The next listing to import: guazi's first, then the oldest. */
    public Optional<Listing> nextQueued(Collection<CarSource> sources) {
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM auto_import_listing WHERE state = 'QUEUED' AND source IN (:sources)"
                        + " ORDER BY CASE source WHEN 'GUAZI' THEN 0 ELSE 1 END, first_seen, listing_id LIMIT 1")
                .param("sources", sources.stream().map(Enum::name).toList())
                .query(MAPPER)
                .optional();
    }

    public void imported(CarSource source, String listingId, long carId, String note) {
        jdbc.sql("""
                        UPDATE auto_import_listing SET state = 'IMPORTED', car_id = :carId, last_error = :note,
                            updated_at = now()
                        WHERE source = :source AND listing_id = :id""")
                .param("carId", carId)
                .param("note", cut(note, 1000), Types.VARCHAR)
                .param("source", source.name())
                .param("id", listingId)
                .update();
    }

    /** Over the mileage limit: not imported (its car, if any, was not saved). */
    public void skipped(CarSource source, String listingId, int mileageKm, String note) {
        jdbc.sql("""
                        UPDATE auto_import_listing SET state = 'SKIPPED', mileage_km = :km, last_error = :note,
                            updated_at = now()
                        WHERE source = :source AND listing_id = :id""")
                .param("km", mileageKm)
                .param("note", cut(note, 1000), Types.VARCHAR)
                .param("source", source.name())
                .param("id", listingId)
                .update();
    }

    public void failed(CarSource source, String listingId, String error) {
        jdbc.sql("""
                        UPDATE auto_import_listing SET
                            attempts = attempts + 1, last_error = :error,
                            state = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE 'QUEUED' END,
                            updated_at = now()
                        WHERE source = :source AND listing_id = :id""")
                .param("error", cut(error, 1000), Types.VARCHAR)
                .param("max", MAX_ATTEMPTS)
                .param("source", source.name())
                .param("id", listingId)
                .update();
    }

    /** source -> state -> count. */
    public Map<String, Map<String, Integer>> stateCounts() {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        jdbc.sql("SELECT source, state, count(*) AS n FROM auto_import_listing GROUP BY source, state")
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getString("source"), k -> new LinkedHashMap<>())
                            .put(rs.getString("state"), rs.getInt("n"));
                    return null;
                })
                .list();
        return out;
    }

    // ---------------------------------------------------------------- cars checked outside the listings

    /** A car checked by its make's search was found: its missed runs start again. */
    public void manualSeen(long carId) {
        jdbc.sql("""
                        INSERT INTO auto_import_manual_check (car_id, missed_passes, last_checked) VALUES (:id, 0, now())
                        ON CONFLICT (car_id) DO UPDATE SET missed_passes = 0, last_checked = now()""")
                .param("id", carId)
                .update();
    }

    /** A reason of the failures with how many listings failed for it (URLs and numbers left out to group them). */
    public record Reason(String reason, long count) {
    }

    private static final String REASON = "regexp_replace(regexp_replace(coalesce(last_error, ''),"
            + " 'https?://[^ )]+', '…', 'g'), '[0-9]+', 'N', 'g')";

    /**
     * A page of the failed listings (state FAILED), the latest first.
     *
     * @param source null = both sites
     * @param text   part of the error, title or listing id; null = all
     */
    public List<Listing> failures(CarSource source, String text, int page, int size) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM auto_import_listing" + failureWhere(source, text)
                        + " ORDER BY updated_at DESC, listing_id LIMIT :limit OFFSET :offset")
                .params(failureParams(source, text))
                .param("limit", size)
                .param("offset", page * size)
                .query(MAPPER)
                .list();
    }

    public long countFailures(CarSource source, String text) {
        return jdbc.sql("SELECT count(*) FROM auto_import_listing" + failureWhere(source, text))
                .params(failureParams(source, text))
                .query(Long.class)
                .single();
    }

    /** The failures grouped by reason, most first. */
    public List<Reason> failureReasons(CarSource source) {
        return jdbc.sql("SELECT " + REASON + " AS reason, count(*) AS n FROM auto_import_listing"
                        + failureWhere(source, null) + " GROUP BY 1 ORDER BY n DESC, reason LIMIT 30")
                .params(failureParams(source, null))
                .query((rs, n) -> new Reason(rs.getString("reason"), rs.getLong("n")))
                .list();
    }

    /**
     * Deletes failed listings from the report. One still at the source is found again by the next run and queued
     * as new.
     */
    public int deleteFailures(Collection<FailureKey> keys) {
        int n = 0;
        for (FailureKey k : keys) {
            n += jdbc.sql("DELETE FROM auto_import_listing WHERE state = 'FAILED' AND source = :source AND listing_id = :id")
                    .param("source", k.source().name())
                    .param("id", k.listingId())
                    .update();
        }
        return n;
    }

    /** Deletes every failure matching the filter (as {@link #failures}). */
    public int deleteFailures(CarSource source, String text) {
        return jdbc.sql("DELETE FROM auto_import_listing" + failureWhere(source, text))
                .params(failureParams(source, text))
                .update();
    }

    public record FailureKey(CarSource source, String listingId) {
    }

    /** Fixed fragments only; the values are parameters. */
    private static String failureWhere(CarSource source, String text) {
        return " WHERE state = 'FAILED'" + (source == null ? "" : " AND source = :source")
                + (text == null || text.isBlank() ? ""
                : " AND (last_error ILIKE :text OR title ILIKE :text OR listing_id ILIKE :text)");
    }

    private static Map<String, Object> failureParams(CarSource source, String text) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (source != null) {
            p.put("source", source.name());
        }
        if (text != null && !text.isBlank()) {
            p.put("text", "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return p;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
