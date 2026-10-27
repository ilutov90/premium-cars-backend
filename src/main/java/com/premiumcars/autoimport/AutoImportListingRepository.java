package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.sql.Types;
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
    /** Failed {@value #MAX_ATTEMPTS} times in a row; queued again by the next run until {@value #GIVE_UP_ATTEMPTS}. */
    public static final String FAILED = "FAILED";
    static final int MAX_ATTEMPTS = 3;
    static final int GIVE_UP_ATTEMPTS = 6;

    public record Listing(CarSource source, String listingId, String url, String brandCode, String title,
                          BigDecimal price, String state, int attempts, String lastError, int missedPasses,
                          Long carId) {
    }

    private static final String COLUMNS = "source, listing_id, url, brand_code, title, price, state, attempts,"
            + " last_error, missed_passes, car_id";

    private static final RowMapper<Listing> MAPPER = (rs, n) -> new Listing(
            CarSource.valueOf(rs.getString("source")), rs.getString("listing_id"), rs.getString("url"),
            rs.getString("brand_code"), rs.getString("title"), rs.getBigDecimal("price"), rs.getString("state"),
            rs.getInt("attempts"), rs.getString("last_error"), rs.getInt("missed_passes"),
            rs.getObject("car_id", Long.class));

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
                        INSERT INTO auto_import_listing (source, listing_id, url, brand_code, title, price, state, car_id)
                        VALUES (:source, :id, :url, :brand, :title, :price, :state, :carId)""")
                .param("source", source.name())
                .param("id", l.id())
                .param("url", l.url())
                .param("brand", brandCode)
                .param("title", cut(l.title(), 300), Types.VARCHAR)
                .param("price", l.price(), Types.NUMERIC)
                .param("state", state)
                .param("carId", carId, Types.BIGINT)
                .update();
    }

    /** Seen again: stamps it, and queues a failed one again unless it failed too often. */
    public void seen(CarSource source, FoundListing l, String brandCode) {
        jdbc.sql("""
                        UPDATE auto_import_listing SET
                            last_seen = now(), missed_passes = 0, brand_code = :brand, title = :title, price = :price,
                            state = CASE WHEN state = 'FAILED' AND attempts < :giveUp THEN 'QUEUED' ELSE state END,
                            updated_at = now()
                        WHERE source = :source AND listing_id = :id""")
                .param("brand", brandCode)
                .param("title", cut(l.title(), 300), Types.VARCHAR)
                .param("price", l.price(), Types.NUMERIC)
                .param("giveUp", GIVE_UP_ATTEMPTS)
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

    /** The latest failures, for the report. */
    public List<Listing> failures(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM auto_import_listing WHERE last_error IS NOT NULL"
                        + " AND state <> 'IMPORTED' ORDER BY updated_at DESC LIMIT :limit")
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
