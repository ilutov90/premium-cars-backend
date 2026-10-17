package com.premiumcars.catalog.domain;

/** A photo that has been stored but not yet saved to the database. */
public record NewCarImage(String storagePath, String sourceUrl) {
}
