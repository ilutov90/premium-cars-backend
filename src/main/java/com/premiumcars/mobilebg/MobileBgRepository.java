package com.premiumcars.mobilebg;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.catalog.repo.JsonColumns;

/** SQL for {@code mobile_bg_catalog} (one row: the last lists an admin's browser sent) and {@code mobile_bg_listing}. */
@Repository
public class MobileBgRepository {

    private final JdbcClient jdbc;
    private final JsonColumns json;

    public MobileBgRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public MobileBgCatalog catalog() {
        return jdbc.sql("SELECT data FROM mobile_bg_catalog WHERE id = 1")
                .query((rs, i) -> json.read(rs.getString("data"), MobileBgCatalog.class))
                .optional()
                .orElse(MobileBgCatalog.EMPTY);
    }

    public void saveCatalog(MobileBgCatalog catalog, String by) {
        jdbc.sql("""
                        INSERT INTO mobile_bg_catalog (id, data, updated_at, updated_by)
                        VALUES (1, CAST(:data AS jsonb), now(), :by)
                        ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = now(),
                            updated_by = EXCLUDED.updated_by""")
                .param("data", json.write(catalog))
                .param("by", by)
                .update();
    }

    public Optional<MobileBgListing> listing(long carId) {
        return jdbc.sql("""
                        SELECT car_id, listing_id, url, published_at, published_by
                        FROM mobile_bg_listing WHERE car_id = :carId""")
                .param("carId", carId)
                .query((rs, i) -> new MobileBgListing(rs.getLong("car_id"), rs.getString("listing_id"),
                        rs.getString("url"), rs.getObject("published_at", OffsetDateTime.class).toInstant(),
                        rs.getString("published_by")))
                .optional();
    }

    /** The last publication wins (a car posted again replaces the record). */
    public MobileBgListing saveListing(long carId, String listingId, String url, Instant at, String by) {
        jdbc.sql("""
                        INSERT INTO mobile_bg_listing (car_id, listing_id, url, published_at, published_by)
                        VALUES (:carId, :listingId, :url, :at, :by)
                        ON CONFLICT (car_id) DO UPDATE SET listing_id = EXCLUDED.listing_id, url = EXCLUDED.url,
                            published_at = EXCLUDED.published_at, published_by = EXCLUDED.published_by""")
                .param("carId", carId)
                .param("listingId", listingId)
                .param("url", url)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("by", by)
                .update();
        return listing(carId).orElseThrow();
    }
}
