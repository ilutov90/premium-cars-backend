package com.premiumcars.seo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.premiumcars.catalog.domain.FuelType;
import com.premiumcars.catalog.domain.Origin;

/** JSON shapes of the SEO landing page endpoints. */
public final class SeoDtos {

    private SeoDtos() {
    }

    public record ModelEntry(String model, String slug, int count, BigDecimal minPriceEur, BigDecimal maxPriceEur,
                             Integer yearFrom, Integer yearTo) {
    }

    public record MakeEntry(String make, String slug, int count, BigDecimal minPriceEur, BigDecimal maxPriceEur,
                            Integer yearFrom, Integer yearTo, List<Origin> origins, List<ModelEntry> models) {
    }

    /** {@code model} / {@code modelSlug} are null on a make page; {@code models} is empty on a model page. */
    public record SeoPage(String make, String makeSlug, String model, String modelSlug, List<String> makeNames,
                          List<String> modelNames, int count, BigDecimal minPriceEur, BigDecimal maxPriceEur,
                          Integer yearFrom, Integer yearTo, List<Origin> origins, List<FuelType> fuels,
                          List<ModelEntry> models, String title, String description, String text) {
    }

    public record AdminSeoPage(String make, String makeSlug, String model, String modelSlug, int count, String title,
                               String description, String text, Instant updatedAt) {
    }

    /** All three texts null = the override is deleted. */
    public record SavePageRequest(String makeSlug, String modelSlug, String title, String description, String text) {
    }
}
