package com.premiumcars.catalog.domain;

/** Lifecycle of a listing. Only PUBLISHED and RESERVED cars are visible on the public site. */
public enum CarStatus {
    DRAFT,
    PUBLISHED,
    RESERVED,
    SOLD,
    ARCHIVED
}
