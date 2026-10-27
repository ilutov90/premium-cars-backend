package com.premiumcars.catalog.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.catalog.domain.CarImage;

/** All SQL for the {@code car_image} table. Position 0 is the cover photo; positions are kept 0..n-1. */
@Repository
public class CarImageRepository {

    private static final RowMapper<CarImage> MAPPER = (rs, n) -> new CarImage(
            rs.getLong("id"),
            rs.getLong("car_id"),
            rs.getString("storage_path"),
            rs.getString("source_url"),
            rs.getInt("position"));

    private final JdbcClient jdbc;

    public CarImageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CarImage> findByCarId(long carId) {
        return jdbc.sql("""
                        SELECT id, car_id, storage_path, source_url, position
                        FROM car_image WHERE car_id = :carId
                        ORDER BY position, id""")
                .param("carId", carId)
                .query(MAPPER)
                .list();
    }

    public Optional<CarImage> findById(long imageId) {
        return jdbc.sql("SELECT id, car_id, storage_path, source_url, position FROM car_image WHERE id = :imageId")
                .param("imageId", imageId)
                .query(MAPPER)
                .optional();
    }

    /** Stored photos of cars from these sources (to turn them into linked photos), oldest first. */
    public List<CarImage> findStored(Collection<String> sources) {
        return jdbc.sql("""
                        SELECT i.id, i.car_id, i.storage_path, i.source_url, i.position
                        FROM car_image i JOIN car c ON c.id = i.car_id
                        WHERE i.storage_path IS NOT NULL AND i.source_url IS NOT NULL AND c.source IN (:sources)
                        ORDER BY i.id""")
                .param("sources", sources)
                .query(MAPPER)
                .list();
    }

    /** Photos in the local folder (storage paths without the "r2:" prefix), a batch at a time. */
    public List<CarImage> findLocal(int limit) {
        return jdbc.sql("""
                        SELECT id, car_id, storage_path, source_url, position
                        FROM car_image WHERE storage_path IS NOT NULL AND storage_path NOT LIKE 'r2:%'
                        ORDER BY id LIMIT :limit""")
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    /** @return false when the photo changed meanwhile (deleted, linked or moved) */
    public boolean moveStoragePath(long imageId, String from, String to) {
        return jdbc.sql("UPDATE car_image SET storage_path = :to WHERE id = :imageId AND storage_path = :from")
                .param("to", to)
                .param("from", from)
                .param("imageId", imageId)
                .update() == 1;
    }

    /** Turns a stored photo into a linked one, shown from {@code url}. */
    public void link(long imageId, String url) {
        jdbc.sql("UPDATE car_image SET storage_path = NULL, source_url = :url WHERE id = :imageId")
                .param("url", url)
                .param("imageId", imageId)
                .update();
    }

    public Optional<CarImage> find(long carId, long imageId) {
        return jdbc.sql("""
                        SELECT id, car_id, storage_path, source_url, position
                        FROM car_image WHERE id = :imageId AND car_id = :carId""")
                .param("imageId", imageId)
                .param("carId", carId)
                .query(MAPPER)
                .optional();
    }

    /** Appends a photo after the existing ones. */
    public long append(long carId, String storagePath, String sourceUrl) {
        return jdbc.sql("""
                        INSERT INTO car_image (car_id, storage_path, source_url, position)
                        VALUES (:carId, :storagePath, :sourceUrl,
                                (SELECT COALESCE(MAX(position) + 1, 0) FROM car_image WHERE car_id = :carId))
                        RETURNING id""")
                .param("carId", carId)
                .param("storagePath", storagePath)
                .param("sourceUrl", sourceUrl)
                .query(Long.class)
                .single();
    }

    public void delete(long carId, long imageId) {
        jdbc.sql("DELETE FROM car_image WHERE id = :imageId AND car_id = :carId")
                .param("imageId", imageId)
                .param("carId", carId)
                .update();
        renumber(carId);
    }

    /** Moves a photo to the front (cover) and shifts the others. */
    public void makeCover(long carId, long imageId) {
        jdbc.sql("UPDATE car_image SET position = -1 WHERE id = :imageId AND car_id = :carId")
                .param("imageId", imageId)
                .param("carId", carId)
                .update();
        renumber(carId);
    }

    /** Rewrites positions as 0..n-1 in their current order. */
    private void renumber(long carId) {
        jdbc.sql("""
                        UPDATE car_image i SET position = r.rn - 1
                        FROM (SELECT id, row_number() OVER (ORDER BY position, id) AS rn
                              FROM car_image WHERE car_id = :carId) r
                        WHERE i.id = r.id AND i.position <> r.rn - 1""")
                .param("carId", carId)
                .update();
    }
}
