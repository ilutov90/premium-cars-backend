package com.premiumcars.catalog.domain;

/**
 * Lifecycle of a listing. Only PUBLISHED and RESERVED cars are visible on the public site. REMOVED: hidden by an
 * earlier version of the automatic import (the listing disappeared from the source); the next run deletes such cars,
 * as it now deletes a car at once when its listing is gone.
 */
public enum CarStatus {
    DRAFT,
    PUBLISHED,
    RESERVED,
    SOLD,
    ARCHIVED,
    REMOVED
}
