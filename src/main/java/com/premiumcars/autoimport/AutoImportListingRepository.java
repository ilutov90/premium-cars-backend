package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    static final int MAX_ATTEMPTS = 3;

    public record Listing(CarSource source, String listingId, String url, String brandCode, String title,
                          BigDecimal price, String state, int attempts, String lastError, int missedPasses,
                          Long carId, Integer mileageKm, Instant updatedAt) {
    }

    private static final String COLUMNS = "source, listing_id, url, brand_code, title, price, state, attempts,"
            + " last_error, missed_passes, car_id, mileage_km, updated_at";

    private static final RowMapper<Listing> MAPPER = (rs, n) -> new Listing(
            CarSource.valueOf(rs.getString("source")), rs.getString("listing_id"), rs.getString("url"),
            rs.getString("brand_code"), rs.getString("title"), rs.getBigDecimal("price"), rs.getString("state"),
            rs.getInt("attempts"), rs.getString("last_error"), rs.getInt("missed_passes"),
            rs.getObject("car_id", Long.class), rs.getObject("mileage_km", Integer.class),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

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
                            (source, listing_id, url, brand_code, title, price, state, car_id, mileage_km)
                        VALUES (:source, :id, :url, :brand, :title, :price, :state, :carId, :km)""")
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
     * Seen again: stamps it, queues a failed one again (with {@value #MAX_ATTEMPTS} new attempts), and follows the mileage limit
     * ({@code maxKm}, null = none): a queued listing over it is skipped, a skipped one under it queued again.
     */
    public void seen(CarSource source, FoundListing l, String brandCode, Integer maxKm) {
        jdbc.sql("""
                        UPDATE auto_import_listing SET
                            last_seen = now(), missed_passes = 0, brand_code = :brand, title = :title, price = :price,
                            mileage_km = COALESCE(:km, mileage_km),
                            state = CASE
                                WHEN state = 'QUEUED' AND :maxKm > 0 AND COALESCE(:km, mileage_km) > :maxKm
                                    THEN 'SKIPPED'
                                WHEN state = 'SKIPPED' AND NOT (:maxKm > 0 AND COALESCE(:km, mileage_km) > :maxKm)
                                    THEN 'QUEUED'
                                WHEN state = 'FAILED' THEN 'QUEUED'
                                ELSE state END,
                            attempts = CASE WHEN state = 'FAILED' THEN 0 ELSE attempts END,
                            updated_at = now()
                        WHERE source = :source AND listing_id = :id""")
                .param("km", l.mileageKm(), Types.INTEGER)
                .param("maxKm", maxKm == null ? 0 : maxKm)
                .param("brand", brandCode)
                .param("title", cut(l.title(), 300), Types.VARCHAR)
                .param("price", l.price(), Types.NUMERIC)
                .param("source", source.name())
                .param("id", l.id())
                .update();
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

    public void delete(CarSource source, String listingId) {
        jdbc.sql("DELETE FROM auto_import_listing WHERE source = :source AND listing_id = :id")
                .param("source", source.name())
                .param("id", listingId)
                .update();
    }

    public int count(CarSource source) {
        return jdbc.sql("SELECT count(*) FROM auto_import_listing WHERE source = :source")
                .param("source", source.name())
                .query(Integer.class)
                .single();
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

    /** It was missing once more. @return how many runs in a row it was missing */
    public int manualMissed(long carId) {
        return jdbc.sql("""
                        INSERT INTO auto_import_manual_check (car_id, missed_passes, last_checked) VALUES (:id, 1, now())
                        ON CONFLICT (car_id) DO UPDATE SET missed_passes = auto_import_manual_check.missed_passes + 1,
                            last_checked = now()
                        RETURNING missed_passes""")
                .param("id", carId)
                .query(Integer.class)
                .single();
    }

    /** How many runs in a row it was missing so far (a dry run reads, never writes). */
    public int manualMissedSoFar(long carId) {
        return jdbc.sql("SELECT missed_passes FROM auto_import_manual_check WHERE car_id = :id")
                .param("id", carId)
                .query(Integer.class)
                .optional()
                .orElse(0);
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
