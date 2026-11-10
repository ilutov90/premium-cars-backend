package com.premiumcars.catalog.domain;

/**
 * Lifecycle of a listing. Only PUBLISHED and RESERVED cars are visible on the public site. A car whose listing
 * disappears from the source has no status of its own: the automatic import deletes it.
 */
public enum CarStatus {
    DRAFT,
    PUBLISHED,
    RESERVED,
    SOLD,
    ARCHIVED
}
