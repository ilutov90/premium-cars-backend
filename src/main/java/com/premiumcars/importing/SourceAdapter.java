package com.premiumcars.importing;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import com.premiumcars.catalog.domain.CarSource;
import com.premiumcars.common.BadRequestException;
import com.premiumcars.media.ImageDownloader.DownloadedImage;

/**
 * One implementation per source site. To support a new site (encar.com, che168.com, a paid API ...),
 * add a Spring bean implementing this interface — nothing else in the import flow changes.
 */
public interface SourceAdapter {

    CarSource source();

    boolean supports(URI url);

    ImportedCar importFrom(URI url);

    /**
     * The listing from its page as the admin's browser saved it (for sites that refuse requests from the server).
     * Sites that are always read from the link refuse it.
     */
    default ImportedCar importFromHtml(URI url, String html) {
        throw new BadRequestException(
                "This site is imported from its link only; leave the saved page out");
    }

    /** The canonical form of a listing URL, used to detect duplicate imports. */
    default URI canonicalUrl(URI url) {
        return url;
    }

    /**
     * True when the site's photo CDN serves the photos to any browser (tested without cookies and referer): they are
     * then not stored, the site shows them from the source's URL (a "linked" photo). Sites that refuse other sites'
     * visitors (autowini: Akamai "Access Denied") keep a stored copy.
     */
    default boolean linksPhotos() {
        return false;
    }

    /**
     * True when a listing without a price must not be imported (unless the admin enters one): guazi (auctions show a
     * stand-in price) and autowini. autohome is still imported as a draft without a price.
     */
    default boolean requiresPrice() {
        return false;
    }

    /** Referer sent when downloading photos (some CDNs refuse requests without it). */
    default String referer(URI listingUrl) {
        return listingUrl.toString();
    }

    /**
     * HTTP client for downloading this site's photos, or null for the shared one. Sites whose photo CDN wants the
     * logged-in session's cookies (autowini) return their session client.
     */
    default HttpClient imageClient() {
        return null;
    }

    /**
     * Second chance for photos the plain download could not get: index in {@code imageUrls} -> photo.
     * autowini loads them in a headless browser; other sites return nothing.
     */
    default Map<Integer, DownloadedImage> imagesFromBrowser(URI listingUrl, List<String> imageUrls) {
        return Map.of();
    }

    /** URLs to try, in order, for one photo (e.g. the large variant first). */
    default List<String> imageCandidates(String imageUrl) {
        return List.of(imageUrl);
    }
}
