package com.premiumcars.social;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL for an uploaded site image's storage path: one row of {@code post_setting}, named by the subclass. */
public abstract class SiteImageRepository {

    private final JdbcClient jdbc;
    private final String name;

    protected SiteImageRepository(JdbcClient jdbc, String name) {
        this.jdbc = jdbc;
        this.name = name;
    }

    public Optional<String> findPath() {
        return jdbc.sql("SELECT value FROM post_setting WHERE name = :name")
                .param("name", name)
                .query(String.class)
                .optional();
    }

    public void savePath(String path) {
        jdbc.sql("""
                        INSERT INTO post_setting (name, value, updated_at) VALUES (:name, :value, now())
                        ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""")
                .param("name", name)
                .param("value", path)
                .update();
    }

    public void delete() {
        jdbc.sql("DELETE FROM post_setting WHERE name = :name").param("name", name).update();
    }
}
