package com.premiumcars.catalog.domain;

/**
 * A photo not yet saved to the database: stored by us ({@code storagePath}), or linked (storagePath null: shown
 * from {@code sourceUrl}, see {@link CarImage}).
 */
public record NewCarImage(String storagePath, String sourceUrl) {
}
