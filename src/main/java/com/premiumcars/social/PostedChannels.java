package com.premiumcars.social;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Where cars were posted through the admin panel: Facebook, Instagram and Viber ({@code social_post}) and mobile.bg
 * ({@code mobile_bg_listing}, reported by the browser extension). Posts made outside the panel are not known.
 * Used by the reports that tell which posts to update (a new price) or take down (the car is gone).
 */
@Repository
public class PostedChannels {

    /**
     * @param network FACEBOOK, INSTAGRAM, VIBER or MOBILE_BG
     * @param url     the post or listing, when known
     */
    public record Post(String network, String url, Instant at) {
    }

    private final JdbcClient jdbc;

    public PostedChannels(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** car id -> its posts, oldest first; cars without any are left out. */
    public Map<Long, List<Post>> of(Collection<Long> carIds) {
        Map<Long, List<Post>> out = new HashMap<>();
        if (carIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                        SELECT car_id, network, permalink AS url, created_at AS at FROM social_post WHERE car_id IN (:ids)
                        UNION ALL
                        SELECT car_id, 'MOBILE_BG', url, published_at FROM mobile_bg_listing WHERE car_id IN (:ids)
                        ORDER BY at""")
                .param("ids", List.copyOf(carIds))
                .query((rs, n) -> {
                    out.computeIfAbsent(rs.getLong("car_id"), k -> new ArrayList<>()).add(new Post(
                            rs.getString("network"), rs.getString("url"),
                            rs.getObject("at", OffsetDateTime.class).toInstant()));
                    return null;
                })
                .list();
        return out;
    }

    public List<Post> of(long carId) {
        return of(List.of(carId)).getOrDefault(carId, List.of());
    }
}
