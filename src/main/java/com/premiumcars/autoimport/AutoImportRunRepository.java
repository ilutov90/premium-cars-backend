package com.premiumcars.autoimport;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

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

    /** A car a run hid (REMOVED), with where it was posted. */
    public record Removed(long id, Long carId, CarSource source, String title, String slug, String sourceUrl,
                          boolean manual, List<PostedChannels.Post> posted, Instant removedAt) {
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

    public List<Run> recent(int limit) {
        return jdbc.sql("""
                        SELECT id, source, dry_run, status, started_at, finished_at, found, new_count, gone_count,
                               returned_count, brands::text AS brands, message, manual_checked, manual_gone
                        FROM auto_import_run ORDER BY started_at DESC, id DESC LIMIT :limit""")
                .param("limit", limit)
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
    public void addRemoved(Long runId, CarSource source, long carId, String title, String slug, String sourceUrl,
                           boolean manual, List<PostedChannels.Post> posted) {
        try {
            jdbc.sql("""
                            INSERT INTO auto_import_removed (run_id, car_id, source, title, slug, source_url, manual, posted)
                            VALUES (:run, :carId, :source, :title, :slug, :url, :manual, CAST(:posted AS jsonb))""")
                    .param("run", runId, Types.BIGINT)
                    .param("carId", carId)
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
