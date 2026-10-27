package com.premiumcars.autoimport;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.catalog.domain.CarSource;

/** SQL for the settings of the automatic import: {@code auto_import_state}, {@code _source}, {@code _brand}, {@code _catalog}. */
@Repository
public class AutoImportSettingsRepository {

    private static final TypeReference<List<CatalogModel>> MODELS = new TypeReference<>() {
    };

    /** A source's switch and filters. */
    public record SourceSettings(CarSource source, boolean enabled, SourceFilters filters, Instant updatedAt) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AutoImportSettingsRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---------------------------------------------------------------- the main switch

    public boolean enabled() {
        return jdbc.sql("SELECT enabled FROM auto_import_state WHERE id = 1").query(Boolean.class).optional()
                .orElse(false);
    }

    public void setEnabled(boolean enabled) {
        jdbc.sql("UPDATE auto_import_state SET enabled = :enabled, updated_at = now() WHERE id = 1")
                .param("enabled", enabled)
                .update();
    }

    // ---------------------------------------------------------------- sources

    public List<SourceSettings> sources() {
        return jdbc.sql("SELECT source, enabled, filters::text AS filters, updated_at FROM auto_import_source"
                        + " ORDER BY CASE source WHEN 'GUAZI' THEN 0 ELSE 1 END, source")
                .query((rs, n) -> new SourceSettings(CarSource.valueOf(rs.getString("source")),
                        rs.getBoolean("enabled"), read(rs.getString("filters"), SourceFilters.class),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public Optional<SourceSettings> source(CarSource source) {
        return sources().stream().filter(s -> s.source() == source).findFirst();
    }

    public void updateSource(CarSource source, boolean enabled, SourceFilters filters) {
        jdbc.sql("""
                        UPDATE auto_import_source
                        SET enabled = :enabled, filters = CAST(:filters AS jsonb), updated_at = now()
                        WHERE source = :source""")
                .param("enabled", enabled)
                .param("filters", write(filters))
                .param("source", source.name())
                .update();
    }

    // ---------------------------------------------------------------- makes to import

    public List<Brand> brands(CarSource source) {
        return jdbc.sql("""
                        SELECT code, name, enabled, year_from, models::text AS models
                        FROM auto_import_brand WHERE source = :source ORDER BY name""")
                .param("source", source.name())
                .query((rs, n) -> new Brand(rs.getString("code"), rs.getString("name"), rs.getBoolean("enabled"),
                        rs.getInt("year_from"), readModels(rs.getString("models"))))
                .list();
    }

    /** Replaces the source's makes (call inside a transaction). */
    public void replaceBrands(CarSource source, List<Brand> brands) {
        jdbc.sql("DELETE FROM auto_import_brand WHERE source = :source").param("source", source.name()).update();
        for (Brand b : brands) {
            jdbc.sql("""
                            INSERT INTO auto_import_brand (source, code, name, enabled, year_from, models)
                            VALUES (:source, :code, :name, :enabled, :yearFrom, CAST(:models AS jsonb))""")
                    .param("source", source.name())
                    .param("code", b.code())
                    .param("name", b.name())
                    .param("enabled", b.enabled())
                    .param("yearFrom", b.yearFrom())
                    .param("models", write(b.models()))
                    .update();
        }
    }

    // ---------------------------------------------------------------- the sources' own lists

    public List<CatalogBrand> catalog(CarSource source) {
        return jdbc.sql("SELECT code, name, models::text AS models FROM auto_import_catalog WHERE source = :source"
                        + " ORDER BY name")
                .param("source", source.name())
                .query((rs, n) -> new CatalogBrand(rs.getString("code"), rs.getString("name"),
                        rs.getString("models") == null ? null : readModels(rs.getString("models"))))
                .list();
    }

    /** Adds or renames makes; their models are replaced only when the list has them (not null). */
    public void saveCatalog(CarSource source, List<CatalogBrand> brands) {
        for (CatalogBrand b : brands) {
            jdbc.sql("""
                            INSERT INTO auto_import_catalog (source, code, name, models)
                            VALUES (:source, :code, :name, CAST(:models AS jsonb))
                            ON CONFLICT (source, code) DO UPDATE SET
                                name = EXCLUDED.name,
                                models = COALESCE(EXCLUDED.models, auto_import_catalog.models),
                                updated_at = now()""")
                    .param("source", source.name())
                    .param("code", b.code())
                    .param("name", b.name())
                    .param("models", b.models() == null ? null : write(b.models()), Types.VARCHAR)
                    .update();
        }
    }

    public void saveModels(CarSource source, String code, String name, List<CatalogModel> models) {
        saveCatalog(source, List.of(new CatalogBrand(code, name, models)));
    }

    // ---------------------------------------------------------------- json

    private List<CatalogModel> readModels(String text) {
        try {
            return text == null ? List.of() : json.readValue(text, MODELS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid models in the database: " + e.getMessage(), e);
        }
    }

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON in the database: " + e.getMessage(), e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
