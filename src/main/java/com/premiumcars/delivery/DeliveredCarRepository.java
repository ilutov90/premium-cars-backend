package com.premiumcars.delivery;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL for the {@code delivered_car} table. */
@Repository
public class DeliveredCarRepository {

    private static final String SELECT = """
            SELECT d.id, d.car_id, c.slug AS car_slug, c.status IN ('PUBLISHED', 'RESERVED') AS car_public,
                   d.car_title, d.customer_name, d.city, d.quote, d.delivered_on, d.photo_path, d.consent,
                   d.published, d.created_at, d.updated_at
            FROM delivered_car d
            LEFT JOIN car c ON c.id = d.car_id
            """;

    /** Newest delivery first; undated ones after the dated, then the newest entry. */
    private static final String ORDER = "ORDER BY d.delivered_on DESC NULLS LAST, d.id DESC ";

    private static final RowMapper<DeliveredCar> MAPPER = (rs, n) -> new DeliveredCar(
            rs.getLong("id"),
            rs.getObject("car_id", Long.class),
            rs.getBoolean("car_public"),
            rs.getString("car_slug"),
            rs.getString("car_title"),
            rs.getString("customer_name"),
            rs.getString("city"),
            rs.getString("quote"),
            rs.getObject("delivered_on", LocalDate.class),
            rs.getString("photo_path"),
            rs.getBoolean("consent"),
            rs.getBoolean("published"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public DeliveredCarRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Fields f) {
        return jdbc.sql("""
                        INSERT INTO delivered_car (car_id, car_title, customer_name, city, quote, delivered_on,
                                                   consent, published)
                        VALUES (:carId, :carTitle, :customerName, :city, :quote, :deliveredOn, :consent, :published)
                        RETURNING id""")
                .params(f.params())
                .query(Long.class)
                .single();
    }

    /** Returns false when it does not exist. */
    public boolean update(long id, Fields f) {
        Map<String, Object> p = f.params();
        p.put("id", id);
        return jdbc.sql("""
                        UPDATE delivered_car
                        SET car_id = :carId, car_title = :carTitle, customer_name = :customerName, city = :city,
                            quote = :quote, delivered_on = :deliveredOn, consent = :consent, published = :published,
                            updated_at = now()
                        WHERE id = :id""")
                .params(p)
                .update() == 1;
    }

    /** Sets (or clears, with null) the photo; returns false when it does not exist. */
    public boolean updatePhoto(long id, String photoPath) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("photoPath", photoPath);
        return jdbc.sql("UPDATE delivered_car SET photo_path = :photoPath, updated_at = now() WHERE id = :id")
                .params(p)
                .update() == 1;
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM delivered_car WHERE id = :id").param("id", id).update() == 1;
    }

    public Optional<DeliveredCar> findById(long id) {
        return jdbc.sql(SELECT + "WHERE d.id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<DeliveredCar> findAll() {
        return jdbc.sql(SELECT + ORDER).query(MAPPER).list();
    }

    public List<DeliveredCar> findPublished(int limit) {
        return jdbc.sql(SELECT + "WHERE d.published " + ORDER + "LIMIT :limit")
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    /** The editable columns. */
    public record Fields(Long carId, String carTitle, String customerName, String city, String quote,
                         LocalDate deliveredOn, boolean consent, boolean published) {

        Map<String, Object> params() {
            Map<String, Object> p = new HashMap<>();
            p.put("carId", carId);
            p.put("carTitle", carTitle);
            p.put("customerName", customerName);
            p.put("city", city);
            p.put("quote", quote);
            p.put("deliveredOn", deliveredOn);
            p.put("consent", consent);
            p.put("published", published);
            return p;
        }
    }
}
