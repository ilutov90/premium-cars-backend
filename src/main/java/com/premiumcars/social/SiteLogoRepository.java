package com.premiumcars.social;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The logo's storage path ({@code SITE_LOGO}) and its style (three more rows) in {@code post_setting}. */
@Repository
public class SiteLogoRepository extends SiteImageRepository {

    static final String NAME = "SITE_LOGO";
    static final String HEIGHT = "LOGO_HEIGHT";
    static final String RADIUS = "LOGO_RADIUS";
    static final String FEATHER = "LOGO_FEATHER";

    private final JdbcClient jdbc;

    public SiteLogoRepository(JdbcClient jdbc) {
        super(jdbc, NAME);
        this.jdbc = jdbc;
    }

    public LogoStyle findStyle() {
        Map<String, String> values = new HashMap<>();
        jdbc.sql("SELECT name, value FROM post_setting WHERE name IN (:names)")
                .param("names", java.util.List.of(HEIGHT, RADIUS, FEATHER))
                .query((rs, i) -> values.put(rs.getString("name"), rs.getString("value")))
                .list();
        return new LogoStyle(number(values.get(HEIGHT)), number(values.get(RADIUS)), number(values.get(FEATHER)));
    }

    /** Replaces the style; a null value clears its row. */
    @Transactional
    public LogoStyle saveStyle(LogoStyle style) {
        save(HEIGHT, style.height());
        save(RADIUS, style.radius());
        save(FEATHER, style.feather());
        return findStyle();
    }

    private void save(String name, Integer value) {
        if (value == null) {
            jdbc.sql("DELETE FROM post_setting WHERE name = :name").param("name", name).update();
        } else {
            jdbc.sql("""
                            INSERT INTO post_setting (name, value, updated_at) VALUES (:name, :value, now())
                            ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""")
                    .param("name", name)
                    .param("value", String.valueOf(value))
                    .update();
        }
    }

    private static Integer number(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
