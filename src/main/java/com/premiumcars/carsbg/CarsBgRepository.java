package com.premiumcars.carsbg;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.catalog.repo.JsonColumns;

/** SQL for {@code cars_bg_catalog} (one row: the last lists an admin's browser sent) and {@code cars_bg_listing}. */
@Repository
public class CarsBgRepository {

    private final JdbcClient jdbc;
    private final JsonColumns json;

    public CarsBgRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public CarsBgCatalog catalog() {
        return jdbc.sql("SELECT data FROM cars_bg_catalog WHERE id = 1")
                .query((rs, i) -> json.read(rs.getString("data"), CarsBgCatalog.class))
                .optional()
                .orElse(CarsBgCatalog.EMPTY);
    }

    public void saveCatalog(CarsBgCatalog catalog, String by) {
        jdbc.sql("""
                        INSERT INTO cars_bg_catalog (id, data, updated_at, updated_by)
                        VALUES (1, CAST(:data AS jsonb), now(), :by)
                        ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, updated_at = now(),
                            updated_by = EXCLUDED.updated_by""")
                .param("data", json.write(catalog))
                .param("by", by)
                .update();
    }

    public Optional<CarsBgListing> listing(long carId) {
        return jdbc.sql("""
                        SELECT car_id, offer_id, url, published_at, published_by
                        FROM cars_bg_listing WHERE car_id = :carId""")
                .param("carId", carId)
                .query((rs, i) -> new CarsBgListing(rs.getLong("car_id"), rs.getString("offer_id"),
                        rs.getString("url"), rs.getObject("published_at", OffsetDateTime.class).toInstant(),
                        rs.getString("published_by")))
                .optional();
    }

    /** The last publication wins (a car posted again after its offer was deleted replaces the record). */
    public CarsBgListing saveListing(long carId, String offerId, String url, Instant at, String by) {
        jdbc.sql("""
                        INSERT INTO cars_bg_listing (car_id, offer_id, url, published_at, published_by)
                        VALUES (:carId, :offerId, :url, :at, :by)
                        ON CONFLICT (car_id) DO UPDATE SET offer_id = EXCLUDED.offer_id, url = EXCLUDED.url,
                            published_at = EXCLUDED.published_at, published_by = EXCLUDED.published_by""")
                .param("carId", carId)
                .param("offerId", offerId)
                .param("url", url)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .param("by", by)
                .update();
        return listing(carId).orElseThrow();
    }

    /** The admin took the offer down on cars.bg: the car may be posted again. */
    public boolean deleteListing(long carId) {
        return jdbc.sql("DELETE FROM cars_bg_listing WHERE car_id = :carId").param("carId", carId).update() > 0;
    }
}
