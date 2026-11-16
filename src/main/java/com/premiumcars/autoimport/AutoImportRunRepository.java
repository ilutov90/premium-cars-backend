package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.autoimport.AutoImportTypes.BrandPass;
import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.social.PostedChannels;

/** SQL for {@code auto_import_run}: one row per run, the admin panel's report. */
@Repository
public class AutoImportRunRepository {

    private static final TypeReference<List<BrandPass>> BRANDS = new TypeReference<>() {
    };

    /**
     * @param status RUNNING, DONE, INCOMPLETE (some makes could not be read whole: nothing of them was removed),
     *               STOPPED (too many cars would have been removed: nothing was removed), FAILED
     */
    public record Run(long id, CarSource source, boolean dryRun, String status, Instant startedAt,
                      Instant finishedAt, int found, int newCount, int goneCount, int returnedCount,
                      List<BrandPass> brands, String message, int manualChecked, int manualGone) {
    }

    /**
     * The counts a run ends with.
     *
     * @param manualChecked cars added by hand (or of makes not imported) checked at the source
     * @param manualGone    of them, hidden because their listing was missing twice
     */
    public record Result(String status, int found, int newCount, int goneCount, int returnedCount,
                         List<BrandPass> brands, String message, int manualChecked, int manualGone) {

        public Result(String status, int found, int newCount, int goneCount, int returnedCount,
                      List<BrandPass> brands, String message) {
            this(status, found, newCount, goneCount, returnedCount, brands, message, 0, 0);
        }
    }

    /** A car a run deleted (its listing was gone), with where it was posted. */
    public record Removed(long id, Long carId, CarSource source, String title, String slug, String sourceUrl,
                          boolean manual, List<PostedChannels.Post> posted, Instant removedAt) {
    }

    /**
     * What a deleted car was, for the statistics of what sells (039); every part may be null.
     *
     * @param make        the make as the source site's own list names it; null = not recognised
     * @param model       the model as that make's menu names it, else the car's own model text
     * @param fuelType    the car's {@code FuelType} name
     * @param fobPriceEur the FOB price in EUR of the kept price breakdown ({@code carPriceEur})
     * @param priceEur    the final customer price
     * @param firstSeen   when its listing was first found by the automatic import, else when the car was created
     */
    public record RemovedFacts(String make, String model, Integer year, String fuelType, Integer mileageKm,
                               BigDecimal fobPriceEur, BigDecimal priceEur, Instant firstSeen) {

        public static final RemovedFacts NONE = new RemovedFacts(null, null, null, null, null, null, null, null);
    }

    /** A noted car without a make (noted before 039, or not recognised): what is left to name it from. */
    public record UnnamedRemoved(long id, CarSource source, String title, String slug) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AutoImportRunRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long start(CarSource source, boolean dryRun) {
        return jdbc.sql("""
                        INSERT INTO auto_import_run (source, dry_run, status) VALUES (:source, :dryRun, 'RUNNING')
                        RETURNING id""")
                .param("source", source.name())
                .param("dryRun", dryRun)
                .query(Long.class)
                .single();
    }

    public void finish(long id, Result r) {
        try {
            jdbc.sql("""
                            UPDATE auto_import_run SET
                                status = :status, finished_at = now(), found = :found, new_count = :newCount,
                                gone_count = :goneCount, returned_count = :returnedCount,
                                brands = CAST(:brands AS jsonb), message = :message,
                                manual_checked = :manualChecked, manual_gone = :manualGone
                            WHERE id = :id""")
                    .param("status", r.status())
                    .param("found", r.found())
                    .param("newCount", r.newCount())
                    .param("goneCount", r.goneCount())
                    .param("returnedCount", r.returnedCount())
                    .param("manualChecked", r.manualChecked())
                    .param("manualGone", r.manualGone())
                    .param("brands", json.writeValueAsString(r.brands()))
                    .param("message", r.message() == null || r.message().length() <= 2000 ? r.message()
                            : r.message().substring(0, 2000), Types.VARCHAR)
                    .param("id", id)
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs left RUNNING by a stop of the server. */
    public void abandonRunning() {
        jdbc.sql("""
                        UPDATE auto_import_run SET status = 'FAILED', finished_at = now(),
                            message = 'Interrupted (the server restarted)'
                        WHERE status = 'RUNNING'""")
                .update();
    }

    /** The export's columns ({@link #export}), in order. */
    public static final List<String> EXPORT_COLUMNS = List.of("id", "dry_run", "status", "started_at", "finished_at",
            "found", "new_count", "gone_count", "returned_count", "manual_checked", "manual_gone", "message", "brands");

    /** The source's last runs, newest first, as text, for the admins' download. */
    public void export(CarSource source, int limit, java.util.function.Consumer<List<String>> row) {
        jdbc.sql("""
                        SELECT id, dry_run, status, started_at, finished_at, found, new_count, gone_count,
                               returned_count, manual_checked, manual_gone, message, brands::text AS brands
                        FROM auto_import_run WHERE source = :source ORDER BY started_at DESC, id DESC LIMIT :limit""")
                .param("source", source.name())
                .param("limit", limit)
                .query((rs, n) -> {
                    List<String> out = new java.util.ArrayList<>(EXPORT_COLUMNS.size());
                    for (int i = 1; i <= EXPORT_COLUMNS.size(); i++) {
                        Object v = rs.getObject(i);
                        out.add(v == null ? "" : v instanceof java.sql.Timestamp t ? t.toInstant().toString()
                                : v.toString());
                    }
                    row.accept(out);
                    return null;
                })
                .list();
    }

    public List<Run> recent(int limit) {
        return page(0, limit);
    }

    /** How many runs there are (the report's pages). */
    public long count() {
        return jdbc.sql("SELECT count(*) FROM auto_import_run").query(Long.class).single();
    }

    /** Runs newest first, {@code limit} of them after the first {@code offset}. */
    public List<Run> page(long offset, int limit) {
        return jdbc.sql("""
                        SELECT id, source, dry_run, status, started_at, finished_at, found, new_count, gone_count,
                               returned_count, brands::text AS brands, message, manual_checked, manual_gone
                        FROM auto_import_run ORDER BY started_at DESC, id DESC LIMIT :limit OFFSET :offset""")
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new Run(rs.getLong("id"), CarSource.valueOf(rs.getString("source")),
                        rs.getBoolean("dry_run"), rs.getString("status"),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("finished_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("finished_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("found"), rs.getInt("new_count"), rs.getInt("gone_count"),
                        rs.getInt("returned_count"), brands(rs.getString("brands")), rs.getString("message"),
                        rs.getInt("manual_checked"), rs.getInt("manual_gone")))
                .list();
    }

    /** Notes a car the run hid; {@code runId} null outside a recorded run (tests). */
    public void addRemoved(Long runId, CarSource source, Long carId, String title, String slug, String sourceUrl,
                           boolean manual, List<PostedChannels.Post> posted) {
        addRemoved(runId, source, carId, title, slug, sourceUrl, manual, posted, RemovedFacts.NONE);
    }

    public void addRemoved(Long runId, CarSource source, Long carId, String title, String slug, String sourceUrl,
                           boolean manual, List<PostedChannels.Post> posted, RemovedFacts facts) {
        addRemoved(runId, source, carId, title, slug, sourceUrl, manual, posted, facts, false);
    }

    /**
     * Notes a car the run hid with what it was ({@code facts}); {@code runId} null outside a recorded run (tests).
     *
     * @param outOfFilters deleted only because it is outside the import's filters: left out of the statistics
     */
    public void addRemoved(Long runId, CarSource source, Long carId, String title, String slug, String sourceUrl,
                           boolean manual, List<PostedChannels.Post> posted, RemovedFacts facts,
                           boolean outOfFilters) {
        RemovedFacts f = facts == null ? RemovedFacts.NONE : facts;
        try {
            jdbc.sql("""
                            INSERT INTO auto_import_removed (run_id, car_id, source, title, slug, source_url, manual, posted,
                                make, model, year, fuel_type, mileage_km, fob_price_eur, price_eur, first_seen,
                                out_of_filters)
                            VALUES (:run, :carId, :source, :title, :slug, :url, :manual, CAST(:posted AS jsonb),
                                :make, :model, :year, :fuel, :km, :fob, :price, :firstSeen, :outOfFilters)""")
                    .param("outOfFilters", outOfFilters)
                    .param("make", cut(f.make(), 100), Types.VARCHAR)
                    .param("model", cut(f.model(), 150), Types.VARCHAR)
                    .param("year", f.year(), Types.INTEGER)
                    .param("fuel", cut(f.fuelType(), 30), Types.VARCHAR)
                    .param("km", f.mileageKm(), Types.INTEGER)
                    .param("fob", f.fobPriceEur(), Types.NUMERIC)
                    .param("price", f.priceEur(), Types.NUMERIC)
                    .param("firstSeen", f.firstSeen() == null ? null
                            : OffsetDateTime.ofInstant(f.firstSeen(), ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("run", runId, Types.BIGINT)
                    .param("carId", carId, Types.BIGINT)
                    .param("source", source.name())
                    .param("title", title == null || title.length() <= 300 ? title : title.substring(0, 300),
                            Types.VARCHAR)
                    .param("slug", slug, Types.VARCHAR)
                    .param("url", sourceUrl == null || sourceUrl.length() <= 1000 ? sourceUrl
                            : sourceUrl.substring(0, 1000), Types.VARCHAR)
                    .param("manual", manual)
                    .param("posted", json.writeValueAsString(posted))
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** What a noted car was (039's columns). */
    public Optional<RemovedFacts> facts(long removedId) {
        return jdbc.sql("""
                        SELECT make, model, year, fuel_type, mileage_km, fob_price_eur, price_eur, first_seen
                        FROM auto_import_removed WHERE id = :id""")
                .param("id", removedId)
                .query((rs, n) -> new RemovedFacts(rs.getString("make"), rs.getString("model"),
                        rs.getObject("year", Integer.class), rs.getString("fuel_type"),
                        rs.getObject("mileage_km", Integer.class), rs.getBigDecimal("fob_price_eur"),
                        rs.getBigDecimal("price_eur"),
                        rs.getObject("first_seen", OffsetDateTime.class) == null ? null
                                : rs.getObject("first_seen", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** Noted cars without a make, after {@code afterId}, by id: a batch for {@link RemovedCarNaming}. */
    public List<UnnamedRemoved> unnamedRemoved(long afterId, int limit) {
        return jdbc.sql("""
                        SELECT id, source, title, slug FROM auto_import_removed
                        WHERE make IS NULL AND id > :after ORDER BY id LIMIT :limit""")
                .param("after", afterId)
                .param("limit", limit)
                .query((rs, n) -> new UnnamedRemoved(rs.getLong("id"), CarSource.valueOf(rs.getString("source")),
                        rs.getString("title"), rs.getString("slug")))
                .list();
    }

    /**
     * Names a noted car still without a make: the make and model where a make was found, the year where it had
     * none. Changes nothing of a row named meanwhile.
     */
    public boolean nameRemoved(long id, String make, String model, Integer year) {
        return jdbc.sql("""
                        UPDATE auto_import_removed SET make = :make, model = COALESCE(:model, model),
                            year = COALESCE(year, :year)
                        WHERE id = :id AND make IS NULL""")
                .param("make", cut(make, 100), Types.VARCHAR)
                .param("model", make == null ? null : cut(model, 150), Types.VARCHAR)
                .param("year", year, Types.INTEGER)
                .param("id", id)
                .update() > 0;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** The cars a run hid, the posted ones first. */
    public List<Removed> removed(long runId) {
        return jdbc.sql("""
                        SELECT id, car_id, source, title, slug, source_url, manual, posted::text AS posted, removed_at
                        FROM auto_import_removed WHERE run_id = :run
                        ORDER BY (posted <> '[]'::jsonb) DESC, title, id""")
                .param("run", runId)
                .query((rs, n) -> new Removed(rs.getLong("id"), rs.getObject("car_id", Long.class),
                        CarSource.valueOf(rs.getString("source")), rs.getString("title"), rs.getString("slug"),
                        rs.getString("source_url"), rs.getBoolean("manual"), posts(rs.getString("posted")),
                        rs.getObject("removed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static final TypeReference<List<PostedChannels.Post>> POSTS = new TypeReference<>() {
    };

    private List<PostedChannels.Post> posts(String text) {
        try {
            return json.readValue(text, POSTS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private List<BrandPass> brands(String text) {
        try {
            return json.readValue(text, BRANDS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
