package com.premiumcars.order;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL for {@code order_contact}: what was said with an order's customer. */
@Repository
public class OrderContactRepository {

    /** @param happenedAt when it happened (an admin may note a call afterwards) */
    public record Contact(long id, long orderId, ContactKind kind, String body, Instant happenedAt,
                          String createdBy, Instant updatedAt) {
    }

    private static final String SELECT =
            "SELECT id, order_id, kind, body, happened_at, created_by, updated_at FROM order_contact ";

    private static final RowMapper<Contact> MAPPER = (rs, n) -> new Contact(rs.getLong("id"),
            rs.getLong("order_id"), ContactKind.valueOf(rs.getString("kind")), rs.getString("body"),
            rs.getObject("happened_at", OffsetDateTime.class).toInstant(), rs.getString("created_by"),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public OrderContactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long orderId, ContactKind kind, String body, Instant happenedAt, String createdBy) {
        return jdbc.sql("""
                        INSERT INTO order_contact (order_id, kind, body, happened_at, created_by)
                        VALUES (:orderId, :kind, :body, :happenedAt, :createdBy)
                        RETURNING id""")
                .param("orderId", orderId)
                .param("kind", kind.name())
                .param("body", body)
                .param("happenedAt", OffsetDateTime.ofInstant(happenedAt, ZoneOffset.UTC))
                .param("createdBy", createdBy)
                .query(Long.class)
                .single();
    }

    public boolean update(long orderId, long id, ContactKind kind, String body, Instant happenedAt) {
        return jdbc.sql("""
                        UPDATE order_contact SET kind = :kind, body = :body, happened_at = :happenedAt,
                            updated_at = now()
                        WHERE order_id = :orderId AND id = :id""")
                .param("orderId", orderId)
                .param("id", id)
                .param("kind", kind.name())
                .param("body", body)
                .param("happenedAt", OffsetDateTime.ofInstant(happenedAt, ZoneOffset.UTC))
                .update() == 1;
    }

    public boolean delete(long orderId, long id) {
        return jdbc.sql("DELETE FROM order_contact WHERE order_id = :orderId AND id = :id")
                .param("orderId", orderId)
                .param("id", id)
                .update() == 1;
    }

    /** Newest first. */
    public List<Contact> findByOrder(long orderId) {
        return jdbc.sql(SELECT + "WHERE order_id = :orderId ORDER BY happened_at DESC, id DESC")
                .param("orderId", orderId)
                .query(MAPPER)
                .list();
    }

    public Optional<Contact> find(long orderId, long id) {
        return jdbc.sql(SELECT + "WHERE order_id = :orderId AND id = :id")
                .param("orderId", orderId)
                .param("id", id)
                .query(MAPPER)
                .optional();
    }
}
