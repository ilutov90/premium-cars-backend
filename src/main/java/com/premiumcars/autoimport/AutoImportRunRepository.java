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
                      List<BrandPass> brands, String message) {
    }

    /** The counts a run ends with. */
    public record Result(String status, int found, int newCount, int goneCount, int returnedCount,
                         List<BrandPass> brands, String message) {
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
                                brands = CAST(:brands AS jsonb), message = :message
                            WHERE id = :id""")
                    .param("status", r.status())
                    .param("found", r.found())
                    .param("newCount", r.newCount())
                    .param("goneCount", r.goneCount())
                    .param("returnedCount", r.returnedCount())
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
                               returned_count, brands::text AS brands, message
                        FROM auto_import_run ORDER BY started_at DESC, id DESC LIMIT :limit""")
                .param("limit", limit)
                .query((rs, n) -> new Run(rs.getLong("id"), CarSource.valueOf(rs.getString("source")),
                        rs.getBoolean("dry_run"), rs.getString("status"),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("finished_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("finished_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("found"), rs.getInt("new_count"), rs.getInt("gone_count"),
                        rs.getInt("returned_count"), brands(rs.getString("brands")), rs.getString("message")))
                .list();
    }

    private List<BrandPass> brands(String text) {
        try {
            return json.readValue(text, BRANDS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
