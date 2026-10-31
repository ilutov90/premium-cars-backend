package com.premiumcars.marketplace;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for {@code marketplace_model_map}: the admins' choices of a listing site's model for ours. */
@Repository
public class ModelMappingRepository {

    private final JdbcClient jdbc;

    public ModelMappingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModelMapping> findAll() {
        return jdbc.sql("""
                        SELECT site, make, model, target_brand, target_model, unavailable, set_by, set_at
                        FROM marketplace_model_map ORDER BY site, make_key, model_key""")
                .query((rs, i) -> new ModelMapping(Site.valueOf(rs.getString("site")), rs.getString("make"),
                        rs.getString("model"), rs.getString("target_brand"), rs.getString("target_model"),
                        rs.getBoolean("unavailable"), rs.getString("set_by"),
                        rs.getObject("set_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public void save(ModelMapping m) {
        jdbc.sql("""
                        INSERT INTO marketplace_model_map (site, make_key, model_key, make, model, target_brand,
                            target_model, unavailable, set_by, set_at)
                        VALUES (:site, :makeKey, :modelKey, :make, :model, :brand, :targetModel, :unavailable, :by, now())
                        ON CONFLICT (site, make_key, model_key) DO UPDATE SET make = EXCLUDED.make,
                            model = EXCLUDED.model, target_brand = EXCLUDED.target_brand,
                            target_model = EXCLUDED.target_model, unavailable = EXCLUDED.unavailable,
                            set_by = EXCLUDED.set_by, set_at = now()""")
                .param("site", m.site().name())
                .param("makeKey", ModelNames.key(m.make()))
                .param("modelKey", ModelNames.modelKey(m.model()))
                .param("make", m.make())
                .param("model", m.model())
                .param("brand", m.unavailable() ? null : m.targetBrand())
                .param("targetModel", m.unavailable() ? null : m.targetModel())
                .param("unavailable", m.unavailable())
                .param("by", m.setBy())
                .update();
    }

    public boolean delete(Site site, String make, String model) {
        return jdbc.sql("""
                        DELETE FROM marketplace_model_map
                        WHERE site = :site AND make_key = :makeKey AND model_key = :modelKey""")
                .param("site", site.name())
                .param("makeKey", ModelNames.key(make))
                .param("modelKey", ModelNames.modelKey(model))
                .update() > 0;
    }
}
