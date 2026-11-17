package com.premiumcars.seo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.premiumcars.catalog.domain.CarStatus;

/** SQL for the SEO landing pages: the public cars' aggregate and the {@code seo_page} overrides. */
@Repository
public class SeoRepository {

    /** The public cars of one make / model spelling, source and fuel type (the grouping the service merges). */
    record Group(String make, String model, String source, String fuel, int cars, BigDecimal minPrice,
                 BigDecimal maxPrice, Integer yearMin, Integer yearMax) {
    }

    /** An override row; the texts are never both null. */
    public record Override(String makeSlug, String modelSlug, String title, String description, String body,
                           java.time.Instant updatedAt, String updatedBy) {
    }

    private final JdbcClient jdbc;

    public SeoRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One aggregate query over the cars with these statuses that have a make; the model is "" when it has none. */
    List<Group> groups(Collection<CarStatus> statuses) {
        return jdbc.sql("""
                        SELECT make, COALESCE(model, '') AS model, source, fuel_type, count(*) AS cars,
                               min(total_price_eur) AS min_price, max(total_price_eur) AS max_price,
                               min(model_year) AS year_min, max(model_year) AS year_max
                        FROM car
                        WHERE status IN (:statuses) AND make IS NOT NULL AND make <> ''
                        GROUP BY make, COALESCE(model, ''), source, fuel_type""")
                .param("statuses", statuses.stream().map(Enum::name).toList())
                .query((rs, n) -> new Group(rs.getString("make"), rs.getString("model"), rs.getString("source"),
                        rs.getString("fuel_type"), rs.getInt("cars"), rs.getBigDecimal("min_price"),
                        rs.getBigDecimal("max_price"), (Integer) rs.getObject("year_min"),
                        (Integer) rs.getObject("year_max")))
                .list();
    }

    public List<Override> findAll() {
        return jdbc.sql("""
                        SELECT make_slug, model_slug, title, description, body, updated_at, updated_by
                        FROM seo_page ORDER BY make_slug, model_slug""")
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<Override> find(String makeSlug, String modelSlug) {
        return jdbc.sql("""
                        SELECT make_slug, model_slug, title, description, body, updated_at, updated_by
                        FROM seo_page WHERE make_slug = :make AND model_slug = :model""")
                .param("make", makeSlug)
                .param("model", modelSlug)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public void save(String makeSlug, String modelSlug, String title, String description, String body, String by) {
        jdbc.sql("""
                        INSERT INTO seo_page (make_slug, model_slug, title, description, body, updated_at, updated_by)
                        VALUES (:make, :model, :title, :description, :body, now(), :by)
                        ON CONFLICT (make_slug, model_slug) DO UPDATE SET title = EXCLUDED.title,
                            description = EXCLUDED.description, body = EXCLUDED.body, updated_at = now(),
                            updated_by = EXCLUDED.updated_by""")
                .param("make", makeSlug)
                .param("model", modelSlug)
                .param("title", title)
                .param("description", description)
                .param("body", body)
                .param("by", by)
                .update();
    }

    public void delete(String makeSlug, String modelSlug) {
        jdbc.sql("DELETE FROM seo_page WHERE make_slug = :make AND model_slug = :model")
                .param("make", makeSlug)
                .param("model", modelSlug)
                .update();
    }

    private static Override map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Override(rs.getString("make_slug"), rs.getString("model_slug"), rs.getString("title"),
                rs.getString("description"), rs.getString("body"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getString("updated_by"));
    }
}
