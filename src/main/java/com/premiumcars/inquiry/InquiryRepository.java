package com.premiumcars.inquiry;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** All SQL for the {@code inquiry} table. */
@Repository
public class InquiryRepository {

    private static final String SELECT = """
            SELECT i.id, i.car_id, c.title AS car_title, c.slug AS car_slug, i.name, i.phone, i.email,
                   i.message, i.status, i.created_at
            FROM inquiry i
            LEFT JOIN car c ON c.id = i.car_id
            """;

    private static final RowMapper<Inquiry> MAPPER = (rs, n) -> {
        OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
        return new Inquiry(
                rs.getLong("id"),
                rs.getObject("car_id", Long.class),
                rs.getString("car_title"),
                rs.getString("car_slug"),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("message"),
                Inquiry.Status.valueOf(rs.getString("status")),
                created == null ? null : created.toInstant());
    };

    private final JdbcClient jdbc;

    public InquiryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Long carId, String name, String phone, String email, String message) {
        Map<String, Object> p = new HashMap<>();
        p.put("carId", carId);
        p.put("name", name);
        p.put("phone", phone);
        p.put("email", email);
        p.put("message", message);
        p.put("status", Inquiry.Status.NEW.name());
        return jdbc.sql("""
                        INSERT INTO inquiry (car_id, name, phone, email, message, status, created_at)
                        VALUES (:carId, :name, :phone, :email, :message, :status, now())
                        RETURNING id""")
                .params(p)
                .query(Long.class)
                .single();
    }

    public Optional<Inquiry> findById(long id) {
        return jdbc.sql(SELECT + "WHERE i.id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /** Newest first; {@code status == null} means all statuses. */
    public List<Inquiry> list(Inquiry.Status status, int limit, int offset) {
        Map<String, Object> p = new HashMap<>();
        p.put("limit", limit);
        p.put("offset", offset);
        String where = "";
        if (status != null) {
            where = "WHERE i.status = :status ";
            p.put("status", status.name());
        }
        return jdbc.sql(SELECT + where + "ORDER BY i.created_at DESC, i.id DESC LIMIT :limit OFFSET :offset")
                .params(p)
                .query(MAPPER)
                .list();
    }

    public long count(Inquiry.Status status) {
        if (status == null) {
            return jdbc.sql("SELECT count(*) FROM inquiry").query(Long.class).single();
        }
        return jdbc.sql("SELECT count(*) FROM inquiry WHERE status = :status")
                .param("status", status.name())
                .query(Long.class)
                .single();
    }

    /** Returns false when the inquiry does not exist. */
    public boolean updateStatus(long id, Inquiry.Status status) {
        return jdbc.sql("UPDATE inquiry SET status = :status WHERE id = :id")
                .param("status", status.name())
                .param("id", id)
                .update() == 1;
    }
}
