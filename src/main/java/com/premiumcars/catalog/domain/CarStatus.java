package com.premiumcars.catalog.domain;

/**
 * Lifecycle of a listing. Only PUBLISHED and RESERVED cars are visible on the public site. REMOVED: the listing
 * disappeared from the source (automatic import); hidden, deleted 30 days later, back to PUBLISHED if it returns.
 */
public enum CarStatus {
    DRAFT,
    PUBLISHED,
    RESERVED,
    SOLD,
    ARCHIVED,
    REMOVED
}
