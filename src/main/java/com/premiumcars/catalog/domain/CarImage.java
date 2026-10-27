package com.premiumcars.catalog.domain;

/**
 * One row of the {@code car_image} table.
 *
 * @param storagePath path inside the media storage, e.g. "cars/15/3f2a.jpg"; the public URL comes from ImageStorage.
 *                    Null for a linked photo: one the site shows straight from the source's CDN (guazi, autohome)
 * @param sourceUrl   the photo's URL on the source site; for a linked photo, the URL the site shows
 * @param position    0 = cover photo
 */
public record CarImage(long id, long carId, String storagePath, String sourceUrl, int position) {

    /** Shown from the source's URL, not stored by us. */
    public boolean linked() {
        return storagePath == null;
    }
}
