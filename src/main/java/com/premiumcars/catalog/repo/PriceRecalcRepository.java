package com.premiumcars.catalog.repo;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.social.PostedChannels.Post;

/** SQL for {@code price_recalc_run} and {@code price_recalc_item}: the price recalculations started by hand. */
@Repository
public class PriceRecalcRepository {

    /**
     * One recalculation.
     *
     * @param scope ALL or a CarSource name
     * @param mode  STORED (the FOB kept at the import) or SOURCE (read again from the listing)
     */
    public record Run(long id, String scope, String mode, String status, String startedBy, Instant startedAt,
                      Instant finishedAt, int total, int processed, int changed, int fixedPrice, int failed,
                      String message) {
    }

    /** A car of the report: its price changed (CHANGED), or its listing could not be read (FAILED). */
    public record Item(long id, Long carId, String kind, String title, String slug, String carStatus, String source,
                       String sourceUrl, BigDecimal oldPriceEur, BigDecimal newPriceEur, BigDecimal oldSourcePrice,
                       BigDecimal newSourcePrice, String sourceCurrency, String note, List<Post> posted) {
    }

    private static final TypeReference<List<Post>> POSTS = new TypeReference<>() {
    };

    private static final RowMapper<Run> RUN = (rs, n) -> new Run(rs.getLong("id"), rs.getString("scope"),
            rs.getString("mode"), rs.getString("status"), rs.getString("started_by"),
            rs.getObject("started_at", OffsetDateTime.class).toInstant(),
            rs.getObject("finished_at", OffsetDateTime.class) == null ? null
                    : rs.getObject("finished_at", OffsetDateTime.class).toInstant(),
            rs.getInt("total"), rs.getInt("processed"), rs.getInt("changed"), rs.getInt("fixed_price"),
            rs.getInt("failed"), rs.getString("message"));

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RowMapper<Item> itemMapper;

    public PriceRecalcRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.itemMapper = (rs, n) -> new Item(rs.getLong("id"), rs.getObject("car_id", Long.class),
                rs.getString("kind"), rs.getString("title"), rs.getString("slug"), rs.getString("car_status"),
                rs.getString("source"), rs.getString("source_url"), rs.getBigDecimal("old_price_eur"),
                rs.getBigDecimal("new_price_eur"), rs.getBigDecimal("old_source_price"),
                rs.getBigDecimal("new_source_price"), rs.getString("source_currency"), rs.getString("note"),
                readPosts(rs.getString("posted")));
    }

    public long start(String scope, String mode, String by, int total) {
        return jdbc.sql("""
                        INSERT INTO price_recalc_run (scope, mode, status, started_by, total)
                        VALUES (:scope, :mode, 'RUNNING', :by, :total) RETURNING id""")
                .param("scope", scope)
                .param("mode", mode)
                .param("by", by, Types.VARCHAR)
                .param("total", total)
                .query(Long.class)
                .single();
    }

    public void progress(long id, int processed, int changed, int fixedPrice, int failed) {
        jdbc.sql("""
                        UPDATE price_recalc_run SET processed = :processed, changed = :changed,
                            fixed_price = :fixedPrice, failed = :failed
                        WHERE id = :id""")
                .param("processed", processed)
                .param("changed", changed)
                .param("fixedPrice", fixedPrice)
                .param("failed", failed)
                .param("id", id)
                .update();
    }

    public void finish(long id, String status, String message) {
        jdbc.sql("UPDATE price_recalc_run SET status = :status, message = :message, finished_at = now() WHERE id = :id")
                .param("status", status)
                .param("message", cut(message, 1000), Types.VARCHAR)
                .param("id", id)
                .update();
    }

    /** At startup: a recalculation cut by a restart stays as far as it got. */
    public void abandonRunning() {
        jdbc.sql("""
                        UPDATE price_recalc_run SET status = 'FAILED', finished_at = now(),
                            message = 'Interrupted (the server restarted): the cars processed so far keep the new price'
                        WHERE status = 'RUNNING'""")
                .update();
    }

    public void addItem(long runId, Item i) {
        jdbc.sql("""
                        INSERT INTO price_recalc_item (run_id, car_id, kind, title, slug, car_status, source, source_url,
                            old_price_eur, new_price_eur, old_source_price, new_source_price, source_currency, note,
                            posted)
                        VALUES (:run, :carId, :kind, :title, :slug, :carStatus, :source, :sourceUrl, :oldPrice,
                            :newPrice, :oldSource, :newSource, :currency, :note, CAST(:posted AS jsonb))""")
                .param("run", runId)
                .param("carId", i.carId(), Types.BIGINT)
                .param("kind", i.kind())
                .param("title", cut(i.title(), 300), Types.VARCHAR)
                .param("slug", i.slug(), Types.VARCHAR)
                .param("carStatus", i.carStatus(), Types.VARCHAR)
                .param("source", i.source(), Types.VARCHAR)
                .param("sourceUrl", cut(i.sourceUrl(), 1000), Types.VARCHAR)
                .param("oldPrice", i.oldPriceEur(), Types.NUMERIC)
                .param("newPrice", i.newPriceEur(), Types.NUMERIC)
                .param("oldSource", i.oldSourcePrice(), Types.NUMERIC)
                .param("newSource", i.newSourcePrice(), Types.NUMERIC)
                .param("currency", i.sourceCurrency(), Types.VARCHAR)
                .param("note", cut(i.note(), 1000), Types.VARCHAR)
                .param("posted", write(i.posted() == null ? List.of() : i.posted()))
                .update();
    }

    public List<Run> recent(int limit) {
        return jdbc.sql("SELECT * FROM price_recalc_run ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query(RUN)
                .list();
    }

    public Optional<Run> find(long id) {
        return jdbc.sql("SELECT * FROM price_recalc_run WHERE id = :id").param("id", id).query(RUN).optional();
    }

    public boolean running() {
        return jdbc.sql("SELECT count(*) FROM price_recalc_run WHERE status = 'RUNNING'").query(Long.class).single() > 0;
    }

    /**
     * A page of a run's report: the posted cars first (their posts need updating), then by title.
     *
     * @param kind   CHANGED, FAILED or null for both
     * @param posted only the cars posted somewhere
     */
    public List<Item> items(long runId, String kind, boolean posted, int page, int size) {
        return jdbc.sql("SELECT * FROM price_recalc_item WHERE run_id = :run" + itemFilter(kind, posted)
                        + " ORDER BY (posted <> '[]'::jsonb) DESC, title, id LIMIT :limit OFFSET :offset")
                .param("run", runId)
                .params(kind == null ? java.util.Map.of() : java.util.Map.of("kind", kind))
                .param("limit", size)
                .param("offset", page * size)
                .query(itemMapper)
                .list();
    }

    public long countItems(long runId, String kind, boolean posted) {
        return jdbc.sql("SELECT count(*) FROM price_recalc_item WHERE run_id = :run" + itemFilter(kind, posted))
                .param("run", runId)
                .params(kind == null ? java.util.Map.of() : java.util.Map.of("kind", kind))
                .query(Long.class)
                .single();
    }

    /** Fixed fragments only; the kind is a parameter. */
    private static String itemFilter(String kind, boolean posted) {
        return (kind == null ? "" : " AND kind = :kind") + (posted ? " AND posted <> '[]'::jsonb" : "");
    }

    public boolean delete(long id) {
        return jdbc.sql("DELETE FROM price_recalc_run WHERE id = :id AND status <> 'RUNNING'")
                .param("id", id)
                .update() == 1;
    }

    private List<Post> readPosts(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, POSTS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid posts in the database: " + e.getMessage(), e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
