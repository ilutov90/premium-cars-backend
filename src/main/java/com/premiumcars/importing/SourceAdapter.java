package com.premiumcars.importing;

import java.net.URI;
import java.util.List;

import com.premiumcars.catalog.domain.CarSource;

/**
 * One implementation per source site. To support a new site (encar.com, che168.com, a paid API ...),
 * add a Spring bean implementing this interface — nothing else in the import flow changes.
 */
public interface SourceAdapter {

    CarSource source();

    boolean supports(URI url);

    ImportedCar importFrom(URI url);

    /** The canonical form of a listing URL, used to detect duplicate imports. */
    default URI canonicalUrl(URI url) {
        return url;
    }

    /** Referer sent when downloading photos (some CDNs refuse requests without it). */
    default String referer(URI listingUrl) {
        return listingUrl.toString();
    }

    /** URLs to try, in order, for one photo (e.g. the large variant first). */
    default List<String> imageCandidates(String imageUrl) {
        return List.of(imageUrl);
    }
}
