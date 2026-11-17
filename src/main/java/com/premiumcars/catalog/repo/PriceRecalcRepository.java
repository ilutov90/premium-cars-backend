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

    /**
     * How far a run got: the cars are taken by id; {@code lastCarId} the last one done (null = none yet),
     * {@code endCarId} the largest id of the run's cars when it started (0 = none), null for runs from before 041.
     */
    public record Position(Long lastCarId, Long endCarId) {

        public boolean resumable() {
            return endCarId != null;
        }
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

    /** @param endCarId the largest id of the run's cars (0 = none): the run never goes past it */
    public long start(String scope, String mode, String by, int total, long endCarId) {
        return jdbc.sql("""
                        INSERT INTO price_recalc_run (scope, mode, status, started_by, total, end_car_id)
                        VALUES (:scope, :mode, 'RUNNING', :by, :total, :endCarId) RETURNING id""")
                .param("scope", scope)
                .param("mode", mode)
                .param("by", by, Types.VARCHAR)
                .param("total", total)
                .param("endCarId", endCarId)
                .query(Long.class)
                .single();
    }

    /** The counters and the last car done ({@code lastCarId} null = none yet: kept as it is). */
    public void progress(long id, int processed, int changed, int fixedPrice, int failed, Long lastCarId) {
        jdbc.sql("""
                        UPDATE price_recalc_run SET processed = :processed, changed = :changed,
                            fixed_price = :fixedPrice, failed = :failed,
                            last_car_id = COALESCE(:lastCarId, last_car_id)
                        WHERE id = :id""")
                .param("processed", processed)
                .param("changed", changed)
                .param("fixedPrice", fixedPrice)
                .param("failed", failed)
                .param("lastCarId", lastCarId, Types.BIGINT)
                .param("id", id)
                .update();
    }

    public Optional<Position> position(long id) {
        return jdbc.sql("SELECT last_car_id, end_car_id FROM price_recalc_run WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Position(rs.getObject("last_car_id", Long.class),
                        rs.getObject("end_car_id", Long.class)))
                .optional();
    }

    /**
     * Gives a run started before 041 a position, so it can continue too: the last car it noted a new price for
     * (the largest {@code car_id} of its items) and {@code endCarId}. That is at or before where it really stopped,
     * so cars may be done twice (no harm: the second time changes nothing) but none is skipped. Empty when the run
     * noted no car, which leaves nothing to go by.
     */
    public Optional<Position> adoptPosition(long id, long endCarId) {
        int n = jdbc.sql("""
                        UPDATE price_recalc_run SET end_car_id = :end,
                            last_car_id = (SELECT max(car_id) FROM price_recalc_item WHERE run_id = :id)
                        WHERE id = :id AND status = 'RUNNING' AND end_car_id IS NULL
                          AND EXISTS (SELECT 1 FROM price_recalc_item WHERE run_id = :id AND car_id IS NOT NULL)""")
                .param("id", id)
                .param("end", endCarId)
                .update();
        return n == 0 ? Optional.empty() : position(id);
    }

    /** Lowers a run's last car to {@code endCarId} (never raises it): the cars it started with, not later ones. */
    public void capEnd(long id, long endCarId) {
        jdbc.sql("UPDATE price_recalc_run SET end_car_id = :end WHERE id = :id AND end_car_id > :end")
                .param("id", id)
                .param("end", endCarId)
                .update();
    }

    /** The runs marked RUNNING (at startup: cut by the restart), the newest first. */
    public List<Run> findRunning() {
        return jdbc.sql("SELECT * FROM price_recalc_run WHERE status = 'RUNNING' ORDER BY id DESC").query(RUN).list();
    }

    /** At startup: a recalculation cut by a restart that cannot continue stays as far as it got. */
    public void abandon(long id) {
        jdbc.sql("""
                        UPDATE price_recalc_run SET status = 'FAILED', finished_at = now(),
                            message = 'Interrupted (the server restarted): the cars processed so far keep the new price'
                        WHERE id = :id AND status = 'RUNNING'""")
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

    /**
     * The ids of the cars of a run's scope ("ALL" or a site) whose final price is set by hand now, by title: the
     * cars a recalculation keeps as they are. Read from the cars themselves, so it also answers for runs started
     * before such cars were noted.
     */
    public List<Long> fixedPriceCarIds(String scope) {
        boolean all = "ALL".equals(scope);
        return jdbc.sql("SELECT id FROM car WHERE fixed_total_eur IS NOT NULL" + (all ? "" : " AND source = :source")
                        + " ORDER BY title, id")
                .params(all ? java.util.Map.of() : java.util.Map.of("source", scope))
                .query(Long.class)
                .list();
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
