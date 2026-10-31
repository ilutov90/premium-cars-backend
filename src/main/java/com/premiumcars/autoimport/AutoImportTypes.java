package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

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

    /**
     * A model as the source names it. For autowini the "sub-model" (e.g. Carnival), for guazi the series.
     *
     * @param excludedFuels for a chosen model: the source's fuel types not to import (autowini's "Fuel Type" names,
     *                      e.g. "Electric Vehicle (EV)"); empty = all
     */
    public record CatalogModel(String code, String name,
                               @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> excludedFuels) {

        public CatalogModel {
            excludedFuels = excludedFuels == null ? List.of() : List.copyOf(excludedFuels);
        }

        public CatalogModel(String code, String name) {
            this(code, name, List.of());
        }
    }

    /** One of the source's fuel types for a make or model, with its number of listings (autowini's facet). */
    public record FuelOption(String code, String name, int count) {
    }

    /** A make in the source's own list; {@code models} null when not loaded yet. */
    public record CatalogBrand(String code, String name, List<CatalogModel> models) {
    }

    /**
     * A make to import.
     *
     * @param models        the chosen models (each with its own excluded fuel types); empty = all models of the make
     * @param excludedFuels when no model is chosen: the fuel types of the whole make not to import; empty = all
     */
    public record Brand(String code, String name, boolean enabled, int yearFrom, List<CatalogModel> models,
                        List<String> excludedFuels) {

        public Brand {
            models = models == null ? List.of() : List.copyOf(models);
            excludedFuels = excludedFuels == null ? List.of() : List.copyOf(excludedFuels);
        }

        public Brand(String code, String name, boolean enabled, int yearFrom, List<CatalogModel> models) {
            this(code, name, enabled, yearFrom, models, List.of());
        }
    }

    /**
     * One listing in the search results. {@code url} is the canonical listing URL (as car.source_url);
     * {@code mileageKm} when the search shows it (else the import checks it).
     */
    public record FoundListing(String id, String url, String title, BigDecimal price, Integer mileageKm) {
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
