package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.util.List;

/** Small value types of the automatic import. */
public final class AutoImportTypes {

    private AutoImportTypes() {
    }

    /**
     * A source's own filters: autowini's "Inspected" and "Actual" (verified mileage); guazi's condition grades
     * (S, A, B, C, D). The other site ignores what is not its own.
     */
    public record SourceFilters(boolean inspected, boolean actualMileage, List<String> grades) {

        public SourceFilters {
            grades = grades == null ? List.of() : List.copyOf(grades);
        }
    }

    /** A model as the source names it. For autowini the "sub-model" (e.g. Carnival), for guazi the series. */
    public record CatalogModel(String code, String name) {
    }

    /** A make in the source's own list; {@code models} null when not loaded yet. */
    public record CatalogBrand(String code, String name, List<CatalogModel> models) {
    }

    /**
     * A make to import.
     *
     * @param models the chosen models; empty = all models of the make
     */
    public record Brand(String code, String name, boolean enabled, int yearFrom, List<CatalogModel> models) {

        public Brand {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    /** One listing in the search results. {@code url} is the canonical listing URL (as car.source_url). */
    public record FoundListing(String id, String url, String title, BigDecimal price) {
    }

    /**
     * How one make's search went in a run.
     *
     * @param expected the source's total for the make (its search's count)
     * @param found    distinct listings read
     * @param complete every result was read: only then can missing listings count as gone
     */
    public record BrandPass(String code, String name, int expected, int found, boolean complete, String error) {
    }

    /** A make's count for the preview. */
    public record BrandCount(String code, String name, Integer count, String error) {
    }
}
