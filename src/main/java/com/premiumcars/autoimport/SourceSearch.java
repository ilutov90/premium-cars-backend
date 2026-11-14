package com.premiumcars.autoimport;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.BrandPass;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.autoimport.AutoImportTypes.FuelOption;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.catalog.domain.CarSource;

/**
 * A source site's search, read through its own JSON API (the one its search page uses). One implementation per site.
 * Calls are paced (a pause between requests) and throw {@link SearchException} when the site does not answer
 * with data.
 */
public interface SourceSearch {

    CarSource source();

    /** The currency of the prices in the search results (both sites: FOB in USD). */
    default String currency() {
        return "USD";
    }

    /**
     * When the site last refused this search for good (a bot check or HTTP 403 that the headless browser met too, or
     * with no browser); null when it never did. The run waits and reads a make again when it was refused.
     */
    default Instant lastRefused() {
        return null;
    }

    /** The site's makes (models are loaded per make, see {@link #models}, unless the list already has them). */
    List<CatalogBrand> brands();

    List<CatalogModel> models(String brandCode);

    /**
     * The fuel types of a make or of one of its models (null = the whole make) from {@code yearFrom}, with their
     * numbers of listings under the filters. Empty when the site has no such filter (guazi).
     */
    default List<FuelOption> fuels(String brandCode, String modelCode, int yearFrom, SourceFilters filters) {
        return List.of();
    }

    /**
     * How many listings match: the make, its year, its chosen models, the fuel types left out and the source's
     * filters.
     */
    int count(Brand brand, SourceFilters filters);

    /**
     * Reads every matching listing (each one once, priced or not) and says whether the whole result was read.
     * Only {@link #priced} listings are imported; the others still count as present at the source.
     */
    BrandPass list(Brand brand, SourceFilters filters, Consumer<FoundListing> found);

    /**
     * Whether one listing is in the site's search (by its listing id), for the check of the cars added by hand.
     *
     * @return null when the site cannot look up one listing (its make is read instead)
     * @throws SearchException when the site does not answer with data (nothing is removed then)
     */
    default Boolean listed(String listingId) {
        return null;
    }

    /** The listing shows a price we can import with. */
    default boolean priced(FoundListing listing) {
        return listing.price() != null && listing.price().signum() > 0;
    }

    /** The site answered, but not with data (blocked, changed, error). */
    class SearchException extends RuntimeException {

        public SearchException(String message) {
            super(message);
        }

        public SearchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
