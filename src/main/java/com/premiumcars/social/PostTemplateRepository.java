package com.premiumcars.social;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code post_template}. */
@Repository
public class PostTemplateRepository {

    private static final RowMapper<PostTemplate> MAPPER = (rs, i) -> new PostTemplate(
            SocialNetwork.valueOf(rs.getString("network")),
            rs.getString("body"),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public PostTemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PostTemplate> findAll() {
        return jdbc.sql("SELECT network, body, updated_at FROM post_template ORDER BY network")
                .query(MAPPER)
                .list();
    }

    public PostTemplate save(SocialNetwork network, String body) {
        return jdbc.sql("""
                        INSERT INTO post_template (network, body, updated_at) VALUES (:network, :body, now())
                        ON CONFLICT (network) DO UPDATE SET body = EXCLUDED.body, updated_at = now()
                        RETURNING network, body, updated_at""")
                .param("network", network.name())
                .param("body", body)
                .query(MAPPER)
                .single();
    }

    /** Back to the default template. */
    public void delete(SocialNetwork network) {
        jdbc.sql("DELETE FROM post_template WHERE network = :network")
                .param("network", network.name())
                .update();
    }
}
