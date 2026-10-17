package com.premiumcars.catalog.domain;

/**
 * One row of the {@code car_image} table.
 *
 * @param storagePath path inside the media storage, e.g. "cars/15/3f2a.jpg"; the public URL comes from ImageStorage
 * @param sourceUrl   original URL on the source site (admin only)
 * @param position    0 = cover photo
 */
public record CarImage(long id, long carId, String storagePath, String sourceUrl, int position) {
}
