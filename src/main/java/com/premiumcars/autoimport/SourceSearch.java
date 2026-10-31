package com.premiumcars.autoimport;

import java.util.List;
import java.util.function.Consumer;

import com.premiumcars.autoimport.AutoImportTypes.Brand;
import com.premiumcars.autoimport.AutoImportTypes.BrandPass;
import com.premiumcars.autoimport.AutoImportTypes.CatalogBrand;
import com.premiumcars.autoimport.AutoImportTypes.CatalogModel;
import com.premiumcars.autoimport.AutoImportTypes.FoundListing;
import com.premiumcars.autoimport.AutoImportTypes.SourceFilters;
import com.premiumcars.catalog.domain.CarSource;

/**
 * A source site's search, read through its own JSON API (the one its search page uses). One implementation per site.
 * Calls are paced (a pause between requests) and throw {@link SearchException} when the site does not answer
 * with data.
 */
public interface SourceSearch {

    CarSource source();

    /** The site's makes (models are loaded per make, see {@link #models}, unless the list already has them). */
    List<CatalogBrand> brands();

    List<CatalogModel> models(String brandCode);

    /** How many listings match: the make, its year, its chosen models and the source's filters. */
    int count(Brand brand, SourceFilters filters);

    /**
     * Reads every matching listing (each one once, priced or not) and says whether the whole result was read.
     * Only {@link #priced} listings are imported; the others still count as present at the source.
     */
    BrandPass list(Brand brand, SourceFilters filters, Consumer<FoundListing> found);

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
