package com.premiumcars.social;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code social_post}. */
@Repository
public class SocialPostRepository {

    private static final RowMapper<SocialPost> MAPPER = (rs, i) -> new SocialPost(
            rs.getLong("id"),
            rs.getLong("car_id"),
            SocialNetwork.valueOf(rs.getString("network")),
            rs.getString("external_id"),
            rs.getString("permalink"),
            rs.getInt("photos"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public SocialPostRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SocialPost> findByCarId(long carId) {
        return jdbc.sql("""
                        SELECT id, car_id, network, external_id, permalink, photos, created_at
                        FROM social_post WHERE car_id = :carId ORDER BY created_at DESC, id DESC""")
                .param("carId", carId)
                .query(MAPPER)
                .list();
    }

    public SocialPost insert(long carId, SocialNetwork network, String externalId, String permalink, int photos) {
        return jdbc.sql("""
                        INSERT INTO social_post (car_id, network, external_id, permalink, photos, created_at)
                        VALUES (:carId, :network, :externalId, :permalink, :photos, now())
                        RETURNING id, car_id, network, external_id, permalink, photos, created_at""")
                .param("carId", carId)
                .param("network", network.name())
                .param("externalId", externalId)
                .param("permalink", permalink)
                .param("photos", photos)
                .query(MAPPER)
                .single();
    }

    /**
     * The latest post of this car on this network made after {@code since}, by hand or published at once: the post of
     * another publication planned for later does not count (two planned posts of one car do not cancel each other).
     */
    public Optional<SocialPost> findPostedAfter(long carId, SocialNetwork network, Instant since) {
        return jdbc.sql("""
                        SELECT id, car_id, network, external_id, permalink, photos, created_at
                        FROM social_post s
                        WHERE car_id = :carId AND network = :network AND created_at > :since
                          AND NOT EXISTS (SELECT 1 FROM scheduled_post p WHERE p.social_post_id = s.id
                                          AND p.publish_at > p.created_at + INTERVAL '1 minute')
                        ORDER BY created_at DESC, id DESC LIMIT 1""")
                .param("carId", carId)
                .param("network", network.name())
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(MAPPER)
                .optional();
    }
}
