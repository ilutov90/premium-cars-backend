package com.premiumcars.inquiry;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.inquiry.InquiryDtos.CarCount;
import com.premiumcars.inquiry.InquiryDtos.Count;
import com.premiumcars.inquiry.InquiryDtos.WeekCount;

/** All SQL for the {@code inquiry} table. */
@Repository
public class InquiryRepository {

    private static final String SELECT = """
            SELECT i.id, i.car_id, c.title AS car_title, c.slug AS car_slug, i.name, i.phone, i.email,
                   i.message, i.status, i.created_at, i.heard_from, i.utm_source, i.utm_medium, i.utm_campaign,
                   i.utm_content, i.landing_path, i.referrer
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
                created == null ? null : created.toInstant(),
                rs.getString("heard_from") == null ? null : HeardFrom.valueOf(rs.getString("heard_from")),
                new Attribution(rs.getString("utm_source"), rs.getString("utm_medium"), rs.getString("utm_campaign"),
                        rs.getString("utm_content"), rs.getString("landing_path"), rs.getString("referrer")));
    };

    /** Days are Sofia days: the report's "from" / "to" and its weeks follow the business's clock. */
    private static final String LOCAL_DAY = "(i.created_at AT TIME ZONE 'Europe/Sofia')::date";
    private static final String PERIOD = "WHERE " + LOCAL_DAY + " BETWEEN :from AND :to ";

    private final JdbcClient jdbc;

    public InquiryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Long carId, String name, String phone, String email, String message, HeardFrom heardFrom,
                       Attribution attribution) {
        Attribution a = attribution == null ? Attribution.NONE : attribution;
        Map<String, Object> p = new HashMap<>();
        p.put("carId", carId);
        p.put("name", name);
        p.put("phone", phone);
        p.put("email", email);
        p.put("message", message);
        p.put("status", Inquiry.Status.NEW.name());
        p.put("heardFrom", heardFrom == null ? null : heardFrom.name());
        p.put("utmSource", a.utmSource());
        p.put("utmMedium", a.utmMedium());
        p.put("utmCampaign", a.utmCampaign());
        p.put("utmContent", a.utmContent());
        p.put("landingPath", a.landingPath());
        p.put("referrer", a.referrer());
        return jdbc.sql("""
                        INSERT INTO inquiry (car_id, name, phone, email, message, status, created_at, heard_from,
                                             utm_source, utm_medium, utm_campaign, utm_content, landing_path, referrer)
                        VALUES (:carId, :name, :phone, :email, :message, :status, now(), :heardFrom,
                                :utmSource, :utmMedium, :utmCampaign, :utmContent, :landingPath, :referrer)
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

    // --- the report (fixed column names only: nothing from the request goes into the SQL text) ---

    public long countBetween(LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT count(*) FROM inquiry i " + PERIOD)
                .param("from", from)
                .param("to", to)
                .query(Long.class)
                .single();
    }

    public List<Count> countByStatus(LocalDate from, LocalDate to) {
        return countBy("i.status", from, to);
    }

    public List<Count> countByHeardFrom(LocalDate from, LocalDate to) {
        return countBy("i.heard_from", from, to);
    }

    public List<Count> countByUtmSource(LocalDate from, LocalDate to) {
        return countBy("lower(i.utm_source)", from, to);
    }

    public List<Count> countByUtmCampaign(LocalDate from, LocalDate to) {
        return countBy("i.utm_campaign", from, to);
    }

    private List<Count> countBy(String column, LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT " + column + " AS k, count(*) AS n FROM inquiry i " + PERIOD
                        + "GROUP BY 1 ORDER BY n DESC, k NULLS LAST")
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Count(rs.getString("k"), rs.getLong("n")))
                .list();
    }

    /** Per car, most first ({@code carId == null}: "find me a car" or a deleted car). */
    public List<CarCount> countByCar(LocalDate from, LocalDate to, int limit) {
        return jdbc.sql("""
                        SELECT i.car_id, c.title, c.slug, count(*) AS n
                        FROM inquiry i
                        LEFT JOIN car c ON c.id = i.car_id
                        """ + PERIOD + """
                        GROUP BY i.car_id, c.title, c.slug
                        ORDER BY n DESC, c.title NULLS LAST
                        LIMIT :limit""")
                .param("from", from)
                .param("to", to)
                .param("limit", limit)
                .query((rs, n) -> new CarCount(rs.getObject("car_id", Long.class), rs.getString("title"),
                        rs.getString("slug"), rs.getLong("n")))
                .list();
    }

    /** Per week (Monday, Sofia time), oldest first; weeks without inquiries are left out. */
    public List<WeekCount> countByWeek(LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT date_trunc('week', " + LOCAL_DAY + ")::date AS w, count(*) AS n FROM inquiry i "
                        + PERIOD + "GROUP BY 1 ORDER BY 1")
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new WeekCount(rs.getObject("w", LocalDate.class), rs.getLong("n")))
                .list();
    }
}
