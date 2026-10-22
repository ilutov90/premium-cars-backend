package com.premiumcars.social;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.catalog.repo.JsonColumns;

/** SQL for {@code scheduled_post}. */
@Repository
public class ScheduledPostRepository {

    private static final String SELECT = """
            SELECT p.id, p.car_id, c.title AS car_title, p.network, p.body, p.image_ids, p.publish_at, p.status,
                   p.attempts, p.error, p.social_post_id, p.created_by, p.created_at, p.updated_at
            FROM scheduled_post p JOIN car c ON c.id = p.car_id""";

    private final JdbcClient jdbc;
    private final JsonColumns json;

    public ScheduledPostRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public ScheduledPost insert(long carId, SocialNetwork network, String body, List<Long> imageIds,
                                Instant publishAt, String createdBy) {
        long id = jdbc.sql("""
                        INSERT INTO scheduled_post (car_id, network, body, image_ids, publish_at, created_by)
                        VALUES (:carId, :network, :body, CAST(:imageIds AS jsonb), :publishAt, :createdBy)
                        RETURNING id""")
                .param("carId", carId)
                .param("network", network.name())
                .param("body", body)
                .param("imageIds", json.write(imageIds == null ? List.of() : imageIds))
                .param("publishAt", utc(publishAt))
                .param("createdBy", createdBy)
                .query(Long.class)
                .single();
        return findById(id).orElseThrow();
    }

    public Optional<ScheduledPost> findById(long id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(this::map).optional();
    }

    public List<ScheduledPost> findByCarId(long carId) {
        return jdbc.sql(SELECT + " WHERE p.car_id = :carId ORDER BY p.publish_at DESC, p.id DESC")
                .param("carId", carId)
                .query(this::map)
                .list();
    }

    /** Waiting ones (soonest first) and those of the last days (newest first), for the overview. */
    public List<ScheduledPost> findUpcomingAndRecent(Instant since) {
        return jdbc.sql(SELECT + """
                         WHERE p.status IN ('SCHEDULED', 'PUBLISHING') OR p.updated_at >= :since
                        ORDER BY CASE WHEN p.status IN ('SCHEDULED', 'PUBLISHING') THEN 0 ELSE 1 END,
                                 CASE WHEN p.status IN ('SCHEDULED', 'PUBLISHING') THEN p.publish_at END ASC,
                                 p.updated_at DESC
                        LIMIT 200""")
                .param("since", utc(since))
                .query(this::map)
                .list();
    }

    /**
     * Takes the due posts for publishing (SCHEDULED -> PUBLISHING) in one statement, so a post is never taken
     * twice even with a second backend running.
     */
    public List<ScheduledPost> claimDue(int limit) {
        List<Long> ids = jdbc.sql("""
                        UPDATE scheduled_post SET status = 'PUBLISHING', attempts = attempts + 1, updated_at = now()
                        WHERE id IN (SELECT id FROM scheduled_post
                                     WHERE status = 'SCHEDULED' AND publish_at <= now()
                                     ORDER BY publish_at LIMIT :limit
                                     FOR UPDATE SKIP LOCKED)
                        RETURNING id""")
                .param("limit", limit)
                .query(Long.class)
                .list();
        return ids.stream().map(this::findById).flatMap(Optional::stream).toList();
    }

    /** Takes one post for publishing now (SCHEDULED -> PUBLISHING); empty when it was taken or cancelled already. */
    public Optional<ScheduledPost> claim(long id) {
        Optional<Long> claimed = jdbc.sql("""
                        UPDATE scheduled_post SET status = 'PUBLISHING', attempts = attempts + 1, updated_at = now()
                        WHERE id = :id AND status = 'SCHEDULED'
                        RETURNING id""")
                .param("id", id)
                .query(Long.class)
                .optional();
        return claimed.flatMap(this::findById);
    }

    public void markPublished(long id, long socialPostId) {
        jdbc.sql("""
                        UPDATE scheduled_post SET status = 'PUBLISHED', social_post_id = :postId, error = NULL,
                            updated_at = now()
                        WHERE id = :id""")
                .param("postId", socialPostId)
                .param("id", id)
                .update();
    }

    public void markFailed(long id, String error) {
        jdbc.sql("UPDATE scheduled_post SET status = 'FAILED', error = :error, updated_at = now() WHERE id = :id")
                .param("error", trim(error))
                .param("id", id)
                .update();
    }

    /** @return false when the post is not waiting any more (published, publishing or cancelled already) */
    public boolean cancel(long id) {
        return jdbc.sql("""
                        UPDATE scheduled_post SET status = 'CANCELLED', updated_at = now()
                        WHERE id = :id AND status = 'SCHEDULED'""")
                .param("id", id)
                .update() > 0;
    }

    /**
     * Posts left PUBLISHING by a stop in the middle of publishing: unknown whether they went out, so they are not
     * retried (no double posts) but marked FAILED for the admin to check.
     */
    public int failInterrupted(Instant olderThan) {
        return jdbc.sql("""
                        UPDATE scheduled_post SET status = 'FAILED', updated_at = now(),
                            error = 'Interrupted while publishing (the server restarted). Check the network before publishing again.'
                        WHERE status = 'PUBLISHING' AND updated_at < :olderThan""")
                .param("olderThan", utc(olderThan))
                .update();
    }

    private ScheduledPost map(ResultSet rs, int row) throws SQLException {
        return new ScheduledPost(
                rs.getLong("id"),
                rs.getLong("car_id"),
                rs.getString("car_title"),
                SocialNetwork.valueOf(rs.getString("network")),
                rs.getString("body"),
                json.readLongList(rs.getString("image_ids")),
                instant(rs, "publish_at"),
                ScheduledPost.Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getString("error"),
                rs.getObject("social_post_id", Long.class),
                rs.getString("created_by"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String trim(String s) {
        return s == null ? null : (s.length() > 1000 ? s.substring(0, 1000) : s);
    }
}
