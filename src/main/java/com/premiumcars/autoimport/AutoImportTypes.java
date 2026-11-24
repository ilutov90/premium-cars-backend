package com.premiumcars.autoimport;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Small value types of the automatic import. */
public final class AutoImportTypes {

    private AutoImportTypes() {
    }

    /**
     * A source's own filters: autowini's "Inspected", "Actual" (verified mileage) and {@code koreaOnly} (the
     * listing's "Location" S.Korea: listings located in Japan or Singapore left out; null, as in filters saved
     * before it, = true); guazi's condition grades (S, A, B, C, D); autotrader.ca's {@link AutotraderFilters} (null
     * for the other sites). Each site ignores what is not its own.
     */
    public record SourceFilters(boolean inspected, boolean actualMileage, List<String> grades,
                                @JsonInclude(JsonInclude.Include.NON_NULL) AutotraderFilters autotrader,
                                Boolean koreaOnly) {

        public SourceFilters {
            grades = grades == null ? List.of() : List.copyOf(grades);
            koreaOnly = koreaOnly == null || koreaOnly;
        }

        public SourceFilters(boolean inspected, boolean actualMileage, List<String> grades,
                             AutotraderFilters autotrader) {
            this(inspected, actualMileage, grades, autotrader, null);
        }

        public SourceFilters(boolean inspected, boolean actualMileage, List<String> grades) {
            this(inspected, actualMileage, grades, null, null);
        }

        /** autotrader.ca's filters, its defaults where none are saved. */
        public AutotraderFilters autotraderOrDefaults() {
            return autotrader == null ? AutotraderFilters.DEFAULTS : autotrader;
        }

        public SourceFilters withAutotrader(AutotraderFilters filters) {
            return new SourceFilters(inspected, actualMileage, grades, filters, koreaOnly);
        }
    }

    /**
     * autotrader.ca's search filters (each null = its default).
     *
     * @param postalCode     the search's centre, a Canadian postal code ("N5P1C5"; stored upper case without spaces)
     * @param radiusKm       the distance from it, 10..400 km
     * @param latitude       the postal code's latitude, when the site needs it with the code (both or none)
     * @param longitude      its longitude
     * @param excludePrivate dealers only (private sellers left out)
     * @param excludeDamaged damaged listings left out (the site's own default)
     * @param excludeNoPrice listings without a price ("call for price") left out of the search
     * @param usedOnly       used cars only; otherwise also demo, employee's, classic and pre-registered cars (never new)
     */
    public record AutotraderFilters(String postalCode, Integer radiusKm, Double latitude, Double longitude,
                                    Boolean excludePrivate, Boolean excludeDamaged, Boolean excludeNoPrice,
                                    Boolean usedOnly) {

        public static final String DEFAULT_POSTAL_CODE = "N5P1C5";
        public static final int DEFAULT_RADIUS_KM = 400;
        /** The coordinates autotrader.ca itself resolved N5P1C5 to (St Thomas, ON): its search URLs carry them. */
        static final double DEFAULT_LATITUDE = 42.779213;
        static final double DEFAULT_LONGITUDE = -81.192156;
        public static final AutotraderFilters DEFAULTS =
                new AutotraderFilters(null, null, null, null, null, null, null, null);

        public AutotraderFilters {
            postalCode = postalCode == null || postalCode.isBlank() ? DEFAULT_POSTAL_CODE
                    : postalCode.replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);
            radiusKm = radiusKm == null ? DEFAULT_RADIUS_KM : radiusKm;
            if (DEFAULT_POSTAL_CODE.equals(postalCode) && latitude == null && longitude == null) {
                latitude = DEFAULT_LATITUDE;
                longitude = DEFAULT_LONGITUDE;
            }
            excludePrivate = excludePrivate == null || excludePrivate;
            excludeDamaged = excludeDamaged == null || excludeDamaged;
            excludeNoPrice = excludeNoPrice == null || excludeNoPrice;
            usedOnly = usedOnly == null || usedOnly;
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
     * {@code mileageKm} when the search shows it (else the import checks it). {@code model}: the source's own model
     * (its menu entry, e.g. autowini's "5 Series") when the listing was read under it; null = unknown, the import
     * takes the model from the listing page.
     */
    public record FoundListing(String id, String url, String title, BigDecimal price, Integer mileageKm,
                               String model) {

        public FoundListing(String id, String url, String title, BigDecimal price, Integer mileageKm) {
            this(id, url, title, price, mileageKm, null);
        }

        FoundListing withModel(String menuModel) {
            return new FoundListing(id, url, title, price, mileageKm, menuModel);
        }
    }

    /**
     * How one make's search went in a run.
     *
     * @param expected the source's total for the make (its search's count)
     * @param found    distinct listings read
     * @param complete every result was read: a listing missing from it is gone
     * @param failed   the site stopped answering: nothing of the make counts as gone
     */
    public record BrandPass(String code, String name, int expected, int found, boolean complete, String error,
                            boolean failed) {
    }

    /**
     * A make's count for the preview.
     *
     * @param count   the site's listings matching now
     * @param leftOut of them, as far as the last runs saw, set aside (over the mileage or price limit, no FOB price for
     *                good): not imported; null when the site was not read
     */
    public record BrandCount(String code, String name, Integer count, Integer leftOut, String error) {
    }
}
