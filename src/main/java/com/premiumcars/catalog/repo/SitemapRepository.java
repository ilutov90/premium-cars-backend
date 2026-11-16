package com.premiumcars.catalog.repo;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the sitemap's page of public cars (PUBLISHED or RESERVED, like the public catalog). */
@Repository
public class SitemapRepository {

    /** A public car: slug, change time and the cover photo as "s:" + storage path or "u:" + a linked photo's URL. */
    public record Row(String slug, java.time.Instant updatedAt, String coverRef) {
    }

    private final JdbcClient jdbc;

    public SitemapRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long countPublic() {
        return jdbc.sql("SELECT count(*) FROM car WHERE status IN ('PUBLISHED', 'RESERVED')")
                .query(Long.class)
                .single();
    }

    public List<Row> findPage(int page, int size) {
        return jdbc.sql("""
                        SELECT c.slug, c.updated_at, cover.ref AS cover_ref
                        FROM car c
                        LEFT JOIN LATERAL (
                            SELECT COALESCE('s:' || i.storage_path, 'u:' || i.source_url) AS ref
                            FROM car_image i WHERE i.car_id = c.id
                            ORDER BY i.position, i.id LIMIT 1) cover ON true
                        WHERE c.status IN ('PUBLISHED', 'RESERVED')
                        ORDER BY c.id
                        LIMIT :limit OFFSET :offset""")
                .param("limit", size)
                .param("offset", (long) page * size)
                .query((rs, n) -> {
                    Timestamp updated = rs.getTimestamp("updated_at");
                    return new Row(rs.getString("slug"), updated == null ? null : updated.toInstant(),
                            rs.getString("cover_ref"));
                })
                .list();
    }
}
