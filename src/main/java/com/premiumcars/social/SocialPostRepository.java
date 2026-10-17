package com.premiumcars.social;

import java.time.OffsetDateTime;
import java.util.List;

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
}
