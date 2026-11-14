package com.premiumcars.social;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the logo's storage path: the {@code SITE_LOGO} row of {@code post_setting}. */
@Repository
public class SiteLogoRepository {

    static final String NAME = "SITE_LOGO";

    private final JdbcClient jdbc;

    public SiteLogoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findPath() {
        return jdbc.sql("SELECT value FROM post_setting WHERE name = :name")
                .param("name", NAME)
                .query(String.class)
                .optional();
    }

    public void savePath(String path) {
        jdbc.sql("""
                        INSERT INTO post_setting (name, value, updated_at) VALUES (:name, :value, now())
                        ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""")
                .param("name", NAME)
                .param("value", path)
                .update();
    }

    public void delete() {
        jdbc.sql("DELETE FROM post_setting WHERE name = :name").param("name", NAME).update();
    }
}
