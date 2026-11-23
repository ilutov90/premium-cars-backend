package com.premiumcars.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.catalog.domain.CarStatus;

/** All SQL for {@code order_candidate_car}: the cars offered to an order's customer. */
@Repository
public class OrderCandidateRepository {

    /**
     * An offered car with what it was when added and its status now.
     *
     * @param carId     null once the car was deleted (the snapshot stays)
     * @param carStatus the car's status now; null when it is gone
     */
    public record Candidate(long id, long orderId, Long carId, String title, String slug, BigDecimal priceEur,
                            String coverUrl, CarStatus carStatus, Instant addedAt, String addedBy) {
    }

    private static final String SELECT = """
            SELECT k.id, k.order_id, k.car_id, k.car_title, k.car_slug, k.car_price_eur, k.cover_url, k.added_at,
                   k.added_by, c.status AS car_status
            FROM order_candidate_car k LEFT JOIN car c ON c.id = k.car_id
            """;

    private static final RowMapper<Candidate> MAPPER = (rs, n) -> {
        String status = rs.getString("car_status");
        return new Candidate(rs.getLong("id"), rs.getLong("order_id"), rs.getObject("car_id", Long.class),
                rs.getString("car_title"), rs.getString("car_slug"), rs.getBigDecimal("car_price_eur"),
                rs.getString("cover_url"), status == null ? null : CarStatus.valueOf(status),
                rs.getObject("added_at", OffsetDateTime.class).toInstant(), rs.getString("added_by"));
    };

    private final JdbcClient jdbc;

    public OrderCandidateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the new row's id, or empty when the order already has this car */
    public Optional<Long> insert(long orderId, long carId, String title, String slug, BigDecimal priceEur,
                                 String coverUrl, String addedBy) {
        return jdbc.sql("""
                        INSERT INTO order_candidate_car (order_id, car_id, car_title, car_slug, car_price_eur,
                            cover_url, added_by)
                        VALUES (:orderId, :carId, :title, :slug, :price, :cover, :addedBy)
                        ON CONFLICT (order_id, car_id) WHERE car_id IS NOT NULL DO NOTHING
                        RETURNING id""")
                .param("orderId", orderId)
                .param("carId", carId)
                .param("title", title)
                .param("slug", slug)
                .param("price", priceEur)
                .param("cover", coverUrl)
                .param("addedBy", addedBy)
                .query(Long.class)
                .optional();
    }

    public List<Candidate> findByOrder(long orderId) {
        return jdbc.sql(SELECT + "WHERE k.order_id = :orderId ORDER BY k.id")
                .param("orderId", orderId)
                .query(MAPPER)
                .list();
    }

    public Optional<Candidate> find(long orderId, long id) {
        return jdbc.sql(SELECT + "WHERE k.order_id = :orderId AND k.id = :id")
                .param("orderId", orderId)
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public boolean delete(long orderId, long id) {
        return jdbc.sql("DELETE FROM order_candidate_car WHERE order_id = :orderId AND id = :id")
                .param("orderId", orderId)
                .param("id", id)
                .update() == 1;
    }
}
