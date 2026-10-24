package com.premiumcars.social;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** SQL for {@code post_setting}: one row per detail; a missing row = the admin panel's default. */
@Repository
public class PostSettingsRepository {

    static final String PHONE = "PHONE";
    static final String VIBER_LINK = "VIBER_LINK";
    static final String OFFICE = "OFFICE";
    static final String META_PIXEL_ID = "META_PIXEL_ID";

    private final JdbcClient jdbc;

    public PostSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PostSettings find() {
        Map<String, String> values = new HashMap<>();
        jdbc.sql("SELECT name, value FROM post_setting")
                .query((rs, i) -> values.put(rs.getString("name"), rs.getString("value")))
                .list();
        return new PostSettings(values.get(PHONE), values.get(VIBER_LINK), values.get(OFFICE), values.get(META_PIXEL_ID));
    }

    /** Replaces all details; an empty one goes back to the default. */
    @Transactional
    public PostSettings save(PostSettings settings) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PHONE, settings.phone());
        values.put(VIBER_LINK, settings.viberLink());
        values.put(OFFICE, settings.office());
        values.put(META_PIXEL_ID, settings.metaPixelId());
        values.forEach((name, value) -> {
            if (value == null) {
                jdbc.sql("DELETE FROM post_setting WHERE name = :name").param("name", name).update();
            } else {
                jdbc.sql("""
                                INSERT INTO post_setting (name, value, updated_at) VALUES (:name, :value, now())
                                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""")
                        .param("name", name)
                        .param("value", value)
                        .update();
            }
        });
        return find();
    }
}
